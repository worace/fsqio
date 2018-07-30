# coding=utf-8
# Copyright 2018 Foursquare Labs Inc. All Rights Reserved.

from __future__ import absolute_import, division, print_function, unicode_literals

from collections import defaultdict
import json

from pants.backend.jvm.subsystems.jvm_platform import JvmPlatform
from pants.backend.jvm.targets.jar_library import JarLibrary
from pants.backend.jvm.targets.jvm_target import JvmTarget
from pants.backend.jvm.targets.scala_library import ScalaLibrary
from pants.backend.project_info.tasks.export import ExportTask
from pants.backend.python.targets.python_requirement_library import PythonRequirementLibrary
from pants.backend.python.targets.python_target import PythonTarget
from pants.base.exceptions import TaskError
from pants.build_graph.resources import Resources
from pants.java.distribution.distribution import DistributionLocator
from pants.java.jar.jar_dependency_utils import M2Coordinate
from pants.task.console_task import ConsoleTask
from pex.pex_info import PexInfo
import six
from twitter.common.collections import OrderedSet

from fsqio.pants.spindle.targets.spindle_thrift_library import SpindleThriftLibrary


class GenStubsAndExportTask(ExportTask):

  @classmethod
  def prepare(cls, options, round_manager):
    # TODO: this method is overriden explicitly for stub generation. When we change the approach we can remove
    super(GenStubsAndExportTask, cls).prepare(options, round_manager)
    if options.libraries or options.libraries_sources or options.libraries_javadocs:
     round_manager.require_data('stubs')

  def generate_targets_map(self, targets, classpath_products=None):
    """Generates a dictionary containing all pertinent information about the target graph.

    The return dictionary is suitable for serialization by json.dumps.
    :param targets: The list of targets to generate the map for.
    :param classpath_products: Optional classpath_products. If not provided when the --libraries
      option is `True`, this task will perform its own jar resolution.

      This is forked from the base export; the only significant changes are reconfiguring targets after resolve_jars and
      filtering synthetic SpindleThriftLibraries from the dependencies.
      This can be a strict call to super if we mess with how dependencies are calculated instead, and reformat targets
      prior to calling this function.
    """
    targets_map = {}
    resource_target_map = {}
    python_interpreter_targets_mapping = defaultdict(list)

    if self.get_options().libraries:
      # NB(gmalmquist): This supports mocking the classpath_products in tests.
      if classpath_products is None:
        classpath_products = self.resolve_jars(targets)
    else:
      classpath_products = None

    # TODO: following line custom to override targets and replace thrift codegen with stubs
    targets = [t for t in targets if not (isinstance(t.derived_from, SpindleThriftLibrary) and t.is_synthetic)]
    target_roots_set = set(self.context.target_roots)

    def process_target(current_target):
      """
      :type current_target:pants.build_graph.target.Target
      """
      def get_target_type(target):
        if target.is_test:
          return ExportTask.SourceRootTypes.TEST
        else:
          if (isinstance(target, Resources) and target in resource_target_map and
                  resource_target_map[target].is_test):

            return ExportTask.SourceRootTypes.TEST_RESOURCE
          elif isinstance(target, Resources):
            return ExportTask.SourceRootTypes.RESOURCE
          else:
            return ExportTask.SourceRootTypes.SOURCE

      info = {
        'targets': [],
        'libraries': [],
        'roots': [],
        'id': current_target.id,
        'target_type': get_target_type(current_target),
        # NB: is_code_gen should be removed when export format advances to 1.1.0 or higher
        'is_code_gen': current_target.is_synthetic,
        'is_synthetic': current_target.is_synthetic,
        'pants_target_type': self._get_pants_target_alias(type(current_target)),
      }

      if not current_target.is_synthetic:
        info['globs'] = current_target.globs_relative_to_buildroot()
        if self.get_options().sources:
          info['sources'] = list(current_target.sources_relative_to_buildroot())

      info['transitive'] = current_target.transitive
      info['scope'] = str(current_target.scope)
      info['is_target_root'] = current_target in target_roots_set

      if isinstance(current_target, PythonRequirementLibrary):
        reqs = current_target.payload.get_field_value('requirements', set())
        """:type : set[pants.backend.python.python_requirement.PythonRequirement]"""
        info['requirements'] = [req.key for req in reqs]

      if isinstance(current_target, PythonTarget):
        interpreter_for_target = self.select_interpreter_for_targets([current_target])
        if interpreter_for_target is None:
          raise TaskError('Unable to find suitable interpreter for {}'
                          .format(current_target.address))
        python_interpreter_targets_mapping[interpreter_for_target].append(current_target)
        info['python_interpreter'] = str(interpreter_for_target.identity)

      def iter_transitive_jars(jar_lib):
        """
        :type jar_lib: :class:`pants.backend.jvm.targets.jar_library.JarLibrary`
        :rtype: :class:`collections.Iterator` of
                :class:`pants.java.jar.M2Coordinate`
        """
        if classpath_products:
          jar_products = classpath_products.get_artifact_classpath_entries_for_targets((jar_lib,))
          for _, jar_entry in jar_products:
            coordinate = jar_entry.coordinate
            # We drop classifier and type_ since those fields are represented in the global
            # libraries dict and here we just want the key into that dict (see `_jar_id`).
            yield M2Coordinate(org=coordinate.org, name=coordinate.name, rev=coordinate.rev)

      target_libraries = OrderedSet()
      if isinstance(current_target, JarLibrary):
        target_libraries = OrderedSet(iter_transitive_jars(current_target))
      # Jbarry note - this is the unforked approach.
      # for de in current_target.dependencies:
      # TODO: following line custom to override targets and replace thrift codegen with stubs.
      for dep in [t for t in current_target.dependencies
                  if not (isinstance(t.derived_from, SpindleThriftLibrary) and t.is_synthetic)]:
        info['targets'].append(dep.address.spec)
        if isinstance(dep, JarLibrary):
          for jar in dep.jar_dependencies:
            target_libraries.add(M2Coordinate(jar.org, jar.name, jar.rev))
          # Add all the jars pulled in by this jar_library
          target_libraries.update(iter_transitive_jars(dep))
        if isinstance(dep, Resources):
          resource_target_map[dep] = current_target

      if isinstance(current_target, ScalaLibrary):
        for dep in current_target.java_sources:
          info['targets'].append(dep.address.spec)
          process_target(dep)

      if isinstance(current_target, JvmTarget):
        info['excludes'] = [self._exclude_id(exclude) for exclude in current_target.excludes]
        info['platform'] = current_target.platform.name
        if hasattr(current_target, 'test_platform'):
          info['test_platform'] = current_target.test_platform.name

      info['roots'] = map(lambda source_root_package_prefix: {
        'source_root': source_root_package_prefix[0],
        'package_prefix': source_root_package_prefix[1]
      }, self._source_roots_for_target(current_target))

      if classpath_products:
        info['libraries'] = [self._jar_id(lib) for lib in target_libraries]
      targets_map[current_target.address.spec] = info

    for target in targets:
      process_target(target)

    jvm_platforms_map = {
      'default_platform': JvmPlatform.global_instance().default_platform.name,
      'platforms': {
        str(platform_name): {
          'target_level': str(platform.target_level),
          'source_level': str(platform.source_level),
          'args': platform.args,
        } for platform_name, platform in JvmPlatform.global_instance().platforms_by_name.items()},
    }

    graph_info = {
      'version': self.DEFAULT_EXPORT_VERSION,
      'targets': targets_map,
      'jvm_platforms': jvm_platforms_map,
    }

    # `jvm_distributions` are static distribution settings from config,
    # `preferred_jvm_distributions` are distributions that pants actually uses for the
    # given platform setting.
    graph_info['preferred_jvm_distributions'] = {}

    def get_preferred_distribution(platform, strict):
      try:
        return JvmPlatform.preferred_jvm_distribution([platform], strict=strict)
      except DistributionLocator.Error:
        return None

    for platform_name, platform in JvmPlatform.global_instance().platforms_by_name.items():
      preferred_distributions = {}
      for strict, strict_key in [(True, 'strict'), (False, 'non_strict')]:
        dist = get_preferred_distribution(platform, strict=strict)
        if dist:
          preferred_distributions[strict_key] = dist.home

      if preferred_distributions:
        graph_info['preferred_jvm_distributions'][platform_name] = preferred_distributions

    if classpath_products:
      graph_info['libraries'] = self._resolve_jars_info(targets, classpath_products)

    if python_interpreter_targets_mapping:
      # NB: We've selected a python interpreter compatible with each python target individually into
      # the `python_interpreter_targets_mapping`. These python targets may not be compatible, ie: we
      # could have a python target requiring 'CPython>=2.7<3' (ie: CPython-2.7.x) and another
      # requiring 'CPython>=3.6'. To pick a default interpreter then from among these two choices
      # is arbitrary and not to be relied on to work as a default interpreter if ever needed by the
      # export consumer.
      #
      # TODO(John Sirois): consider either eliminating the 'default_interpreter' field and pressing
      # export consumers to make their own choice of a default (if needed) or else use
      # `select.select_interpreter_for_targets` and fail fast if there is no interpreter compatible
      # across all the python targets in-play.
      #
      # For now, make our arbitrary historical choice of a default interpreter explicit and use the
      # lowest version.
      default_interpreter = min(python_interpreter_targets_mapping.keys())

      interpreters_info = {}
      for interpreter, targets in six.iteritems(python_interpreter_targets_mapping):
        chroot = self.cached_chroot(
          interpreter=interpreter,
          pex_info=PexInfo.default(),
          targets=targets
        )
        interpreters_info[str(interpreter.identity)] = {
          'binary': interpreter.binary,
          'chroot': chroot.path()
        }

      graph_info['python_setup'] = {
        'default_interpreter': str(default_interpreter.identity),
        'interpreters': interpreters_info
      }

    return graph_info


class GenStubsAndExport(GenStubsAndExportTask, ConsoleTask):
  """Export project information in JSON format.

  Intended for exporting project information for IDE, such as the IntelliJ Pants plugin.
  TODO: we can back this out entirely when we introduce stubs to pants more formally.
  """

  def __init__(self, *args, **kwargs):
    super(GenStubsAndExport, self).__init__(*args, **kwargs)

  def console_output(self, targets, classpath_products=None):
    graph_info = self.generate_targets_map(targets, classpath_products=classpath_products)

    if self.get_options().formatted:
      return json.dumps(graph_info, indent=4, separators=(',', ': ')).splitlines()
    else:
      return [json.dumps(graph_info)]

package com.uber.okbuck

import com.uber.okbuck.core.dependency.DependencyCache
import com.uber.okbuck.core.model.base.TargetCache
import com.uber.okbuck.core.model.java.JavaLibTarget
import com.uber.okbuck.core.task.OkBuckCleanTask
import com.uber.okbuck.core.util.LintUtil
import com.uber.okbuck.core.util.RetrolambdaUtil
import com.uber.okbuck.core.util.RobolectricUtil
import com.uber.okbuck.core.util.TransformUtil
import com.uber.okbuck.extension.ExperimentalExtension
import com.uber.okbuck.extension.IntellijExtension
import com.uber.okbuck.extension.LintExtension
import com.uber.okbuck.extension.OkBuckExtension
import com.uber.okbuck.extension.RetrolambdaExtension
import com.uber.okbuck.extension.TestExtension
import com.uber.okbuck.extension.TransformExtension
import com.uber.okbuck.extension.WrapperExtension
import com.uber.okbuck.generator.BuckFileGenerator
import com.uber.okbuck.generator.DotBuckConfigLocalGenerator
import com.uber.okbuck.wrapper.BuckWrapperTask
import org.apache.commons.io.IOUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.logging.Logger

class OkBuckGradlePlugin implements Plugin<Project> {

    static final String EXTERNAL_DEP_BUCK_FILE = "thirdparty/BUCK_FILE"
    static final String OKBUCK = "okbuck"
    static final String OKBUCK_CLEAN = 'okbuckClean'
    static final String BUCK = "BUCK"
    static final String EXPERIMENTAL = "experimental"
    static final String INTELLIJ = "intellij"
    static final String TEST = "test"
    static final String WRAPPER = "wrapper"
    static final String BUCK_WRAPPER = "buckWrapper"
    static final String DEFAULT_CACHE_PATH = ".okbuck/cache"
    static final String GROUP = "okbuck"
    static final String BUCK_LINT = "buckLint"
    static final String LINT = "lint"
    static final String TRANSFORM = "transform"
    static final String RETROLAMBDA = "retrolambda"
    static final String CONFIGURATION_EXTERNAL = "externalOkbuck"

    static DependencyCache depCache
    static Logger LOGGER

    void apply(Project project) {
        LOGGER = project.logger
        OkBuckExtension okbuckExt = project.extensions.create(OKBUCK, OkBuckExtension, project)
        WrapperExtension wrapper = okbuckExt.extensions.create(WRAPPER, WrapperExtension)
        ExperimentalExtension experimental = okbuckExt.extensions.create(EXPERIMENTAL, ExperimentalExtension)
        TestExtension test = okbuckExt.extensions.create(TEST, TestExtension)
        IntellijExtension intellij = okbuckExt.extensions.create(INTELLIJ, IntellijExtension)
        LintExtension lint = okbuckExt.extensions.create(LINT, LintExtension, project)
        RetrolambdaExtension retrolambda = okbuckExt.extensions.create(RETROLAMBDA, RetrolambdaExtension)
        okbuckExt.extensions.create(TRANSFORM, TransformExtension)

        Task okBuck = project.task(OKBUCK)
        okBuck.setGroup(GROUP)
        okBuck.setDescription("Generate BUCK files")
        okBuck.outputs.upToDateWhen { false }

        Task okbuckSetupTask = project.tasks.create("okbuckSetupTask")

        project.configurations.maybeCreate(TransformUtil.CONFIGURATION_TRANSFORM)
        Configuration externalOkbuck = project.configurations.maybeCreate(CONFIGURATION_EXTERNAL)

        project.afterEvaluate {
            Task okBuckClean = project.tasks.create(OKBUCK_CLEAN, OkBuckCleanTask, {
                dir = project.projectDir.absolutePath
                includes = wrapper.remove
                excludes = wrapper.keep
            })
            okBuckClean.setGroup(GROUP)
            okBuckClean.setDescription("Delete configuration files generated by OkBuck")

            okBuck.dependsOn(okBuckClean)
            okBuck.dependsOn(okbuckSetupTask)

            Task buildDepCache = project.task('buildDepCache')
            okbuckSetupTask.dependsOn(buildDepCache)
            buildDepCache.mustRunAfter(okBuckClean)

            okBuck.doLast {
                generate(project, okbuckExt)
                if (!experimental.parallel) {
                    okbuckExt.buckProjects.each { Project subProject ->
                        BuckFileGenerator.generate(subProject)
                    }
                }
            }

            buildDepCache.doLast {
                addSubProjectRepos(project as Project, okbuckExt.buckProjects as Set<Project>)
                Set<Configuration> projectConfigurations = configurations(okbuckExt.buckProjects)
                projectConfigurations.addAll([externalOkbuck])

                depCache = new DependencyCache(
                        "external",
                        project,
                        DEFAULT_CACHE_PATH,
                        projectConfigurations,
                        EXTERNAL_DEP_BUCK_FILE,
                        true,
                        true,
                        intellij.sources,
                        experimental.lint,
                        okbuckExt.buckProjects)
            }

            if (experimental.parallel) {
                createSubTasks(project, okbuckSetupTask)
            }

            BuckWrapperTask buckWrapper = project.tasks.create(BUCK_WRAPPER, BuckWrapperTask, {
                repo = wrapper.repo
                remove = wrapper.remove
                keep = wrapper.keep
                watch = wrapper.watch
                sourceRoots = wrapper.sourceRoots
            })
            buckWrapper.setGroup(GROUP)
            buckWrapper.setDescription("Create buck wrapper")

            if (test.robolectric) {
                Task fetchRobolectricRuntimeDeps = project.task('fetchRobolectricRuntimeDeps')
                okbuckSetupTask.dependsOn(fetchRobolectricRuntimeDeps)
                fetchRobolectricRuntimeDeps.mustRunAfter(okBuckClean)
                fetchRobolectricRuntimeDeps.setDescription("Fetches runtime dependencies for robolectric")

                fetchRobolectricRuntimeDeps.doLast {
                    RobolectricUtil.download(project)
                }
            }

            if (experimental.lint) {
                okbuckExt.buckProjects.each { Project buckProject ->
                    buckProject.configurations.maybeCreate(BUCK_LINT)
                }

                Task fetchLintDeps = project.task('fetchLintDeps')
                okbuckSetupTask.dependsOn(fetchLintDeps)
                fetchLintDeps.mustRunAfter(okBuckClean)
                fetchLintDeps.doLast {
                    LintUtil.fetchLintDeps(project, lint.version)
                }
            }

            if (experimental.transform) {
                Task fetchTransformDeps = project.task('fetchTransformDeps')
                okbuckSetupTask.dependsOn(fetchTransformDeps)
                fetchTransformDeps.mustRunAfter(okBuckClean)
                fetchTransformDeps.doLast { TransformUtil.fetchTransformDeps(project) }
            }

            if (experimental.retrolambda) {
                Task fetchRetrolambdaDeps = project.task('fetchRetrolambdaDeps')
                okbuckSetupTask.dependsOn(fetchRetrolambdaDeps)
                fetchRetrolambdaDeps.mustRunAfter(okBuckClean)
                fetchRetrolambdaDeps.doLast {
                    RetrolambdaUtil.fetchRetrolambdaDeps(project, retrolambda)
                }
            }
        }
    }

    private static void generate(Project project, OkBuckExtension okbuckExt) {
        // generate empty .buckconfig if it does not exist
        File dotBuckConfig = project.file(".buckconfig")
        if (!dotBuckConfig.exists()) {
            dotBuckConfig.createNewFile()
        }

        // generate .buckconfig.local
        File dotBuckConfigLocal = project.file(".buckconfig.local")
        PrintStream configPrinter = new PrintStream(dotBuckConfigLocal)
        DotBuckConfigLocalGenerator.generate(okbuckExt).print(configPrinter)
        IOUtils.closeQuietly(configPrinter)
    }

    private static Set<Configuration> configurations(Set<Project> projects) {
        Set<Configuration> configurations = new HashSet()
        projects.each { Project p ->
            TargetCache.getTargets(p).values().each {
                if (it instanceof JavaLibTarget) {
                    configurations.addAll(it.depConfigurations())
                }
            }
        }
        return configurations
    }

    private static void createSubTasks(Project project, Task okbuckSetupTask) {
        OkBuckExtension okbuck = project.okbuck
        okbuck.buckProjects.each { Project subProject ->
            Task okbuckProjectTask = subProject.tasks.maybeCreate(OKBUCK)
            okbuckProjectTask.doLast {
                BuckFileGenerator.generate(subProject)
            }
            okbuckProjectTask.dependsOn(okbuckSetupTask)
        }
    }

    /**
     * This is required to let the root project super configuration resolve
     * all recursively copied configurations.
     */
    private static void addSubProjectRepos(Project rootProject, Set<Project> subProjects) {
        Map<Object, ArtifactRepository> reduced = [:]

        subProjects.each { Project subProject ->
            subProject.repositories.asMap.values().each {
                if (it instanceof MavenArtifactRepository) {
                    reduced.put(it.url, it)
                } else if (it instanceof IvyArtifactRepository) {
                    reduced.put(it.url, it)
                } else if (it instanceof FlatDirectoryArtifactRepository) {
                    reduced.put(it.dirs, it)
                } else {
                    rootProject.repositories.add(it)
                }
            }
        }

        rootProject.repositories.addAll(reduced.values())
    }
}

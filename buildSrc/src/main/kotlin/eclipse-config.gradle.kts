import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry
import org.gradle.plugins.ide.eclipse.model.ClasspathEntry
import org.gradle.plugins.ide.eclipse.model.Container

plugins {
    eclipse
}

eclipse {
    classpath {
        isDownloadJavadoc = true
        isDownloadSources = true
        file {
            whenMerged {
                val cp = this as org.gradle.plugins.ide.eclipse.model.Classpath
                cp.entries.filter { entry ->
                    entry.kind == "lib" || entry.kind == "con"
                }.filter { entry ->
                    (entry as AbstractClasspathEntry).entryAttributes["gradle_used_by_scope"] != "test"
                }.forEach { entry ->
                    (entry as AbstractClasspathEntry).entryAttributes["module"] = "true"
                }

                cp.entries.filter { entry ->
                    (entry.kind == "src" || entry.kind == "lib") &&
                        (entry as AbstractClasspathEntry).entryAttributes["gradle_used_by_scope"] == "test"
                }.forEach { entry ->
                    (entry as AbstractClasspathEntry).entryAttributes["test"] = "true"
                }

                cp.entries.filter { entry -> isConGradle(entry) }.forEach { entry ->
                    (entry as AbstractClasspathEntry).entryAttributes["module"] = "true"
                }
            }
        }
    }
}

fun isConGradle(entry: ClasspathEntry): Boolean {
    return entry.kind == "con" && entry is Container && entry.path == "org.eclipse.buildship.core.gradleclasspathcontainer"
}

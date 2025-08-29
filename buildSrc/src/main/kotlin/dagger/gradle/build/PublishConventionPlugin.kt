/*
 * Copyright (C) 2025 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.gradle.build

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A convention plugin that sets the default configuration for published projects in Dagger's
 * codebase. The plugin configures the POM information of Maven artifacts to publish and the name of
 * the project is used as artifact id.
 *
 * This plugin can be applied using:
 * ```
 * plugins {
 *   alias(libs.plugins.dagger.publish)
 * }
 * ```
 */
class PublishConventionPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply(project.getPluginIdByName("publish"))

    project.plugins.withId(project.getPluginIdByName("publish")) {
      val publishExtension =
        project.extensions.getByName("mavenPublishing") as MavenPublishBaseExtension
      publishExtension.apply {
        coordinates(
          groupId = "me.gulya.dagger",
          artifactId = project.name,
          version = project.findProperty("PUBLISH_VERSION").toString(),
        )
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        pom {
          name.set(project.name.asPomName())
          description.set("A fast dependency injector for Android and Java.")
          url.set("https://github.com/google/dagger")
          scm {
            url.set("https://github.com/google/dagger/")
            connection.set("scm:git:git://github.com/google/dagger.git")
          }
          issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/google/dagger/issues")
          }
          licenses {
            license {
              name.set("The Apache Software License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          organization {
            name.set("Google, Inc.")
            url.set("https://www.google.com")
          }
        }
      }
    }
  }

  /**
   * Converts the Gradle project name to a more appropriate name for the POM file.
   *
   * For example: 'dagger-compiler' to 'Dagger Compiler'
   */
  private fun String.asPomName(): String {
    val parts = split("-").map { first().uppercaseChar() + drop(1) }
    return parts.joinToString(separator = " ")
  }
}

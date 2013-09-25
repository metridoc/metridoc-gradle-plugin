package metridoc.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Upload
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.execution.taskgraph.DefaultTaskGraphExecuter

/**
 * Created with IntelliJ IDEA on 8/6/13
 * @author Tommy Barker
 */
class MetridocGradlePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        Upload installTask = project.tasks.withType(Upload).findByName('install');
        if (!installTask) {
            project.apply(plugin: "maven")
            installTask = project.tasks.withType(Upload).findByName('install');
            println installTask
        }

        project.ext.set("enableMaven") { Closure configurePom ->
            project.uploadArchives {
                repositories {
                    mavenDeployer {
                        repository(
                                id: project.properties.mavenRepoId ?: "Metridoc bintray repo",
                                url: project.properties.mavenRepoUrl ?: "https://api.bintray.com/maven/upennlib/metridoc/${project.properties.archivesBaseName}",
                        ) {
                            authentication(userName: project.properties.bintrayUsername, password: project.properties.bintrayPassword)
                        }

                        if (configurePom) {
                            configurePom.clone().call(pom)
                        }
                    }
                }
            }

            project.install {
                repositories.mavenInstaller { installer ->
                    if (configurePom) {
                        configurePom.clone().call(pom)
                    }
                }
            }

            project.artifacts {
                archives(project.tasks.findByName("packageJavadoc")) {
                    type = 'javadoc'
                }

                archives(project.tasks.findByName("packageSources"))
            }
        }

        project.task("packageJavadoc", type: Jar, dependsOn: 'groovydoc') {
            from project.groovydoc.destinationDir
            classifier = 'javadoc'
        }

        project.task("packageSources", type: Jar, dependsOn: 'classes') {
            from project.sourceSets.main.allSource
            classifier = 'sources'
        }

        project.ext.set("metridocJobCore") {
            if (it) {
                return "com.github.metridoc:metridoc-job-core:$it"
            }
            else {
                return 'com.github.metridoc:metridoc-job-core:latest.integration'
            }
        }

        project.ext.set("metridocToolGorm") {
            if (it) {
                return "com.github.metridoc:metridoc-tool-gorm:$it"
            }
            else {
                return 'com.github.metridoc:metridoc-tool-gorm:latest.integration'
            }
        }

        project.task("wrapper", type: Wrapper) {
            gradleVersion = project.hasProperty("gradleWrapperVersion") ? project.gradleWrapperVersion : "1.7"
        }

        project.task("uploadToBintray", dependsOn: ["prepareForBintrayUpload", "uploadArchives", "publishArtifacts"])
        project.task("prepareForBintrayUpload") << {

            def uploadArchives = project.tasks.findByName("uploadArchives")
            def publishArtifacts = project.tasks.findByName("publishArtifacts")
            def tasks = [uploadArchives, publishArtifacts]

            if (!project.version || "unspecified" == project.version) {
                project.logger.warn "a project version is required to upload to bintray, [uploadToBintray] task has been skipped"
                tasks*.enabled = false
                return
            }

            if (project.version.toString().contains("SNAPSHOT")) {
                println "bintray does not support SNAPSHOTs, skipping upload to bintray"
                tasks*.enabled = false
                return
            }

            if (!project.hasProperty("bintrayUsername") || !project.hasProperty("bintrayPassword")) {
                println "bintray credentials not setup, skipping upload to bintray"
                tasks*.enabled = false
                return
            }

            def json = new URL("https://api.bintray.com/packages/upennlib/metridoc/${project.properties.archivesBaseName}").text
            def slurper = new JsonSlurper()
            def versions = slurper.parseText(json).versions
            def versionAlreadyDeployed = versions.contains(project.version.toString())

            if (versionAlreadyDeployed) {
                println "version $project.version has already been deployed to bintray, skipping upload to bintray"
                tasks*.enabled = false
            }
        }
        def uploadArchives = project.tasks.findByName("uploadArchives")

        uploadArchives.dependsOn("prepareForBintrayUpload")

        project.task("publishArtifacts", dependsOn: ["prepareForBintrayUpload", "uploadArchives"]) << {
            def shouldPublish = project.hasProperty("publish") && Boolean.valueOf(project.properties.publish)
            if (shouldPublish) {
                def bintrayRepo = "https://api.bintray.com/content/upennlib/metridoc/" +
                        "${project.properties.archivesBaseName}/$project.version/publish"
                project.logger.info "publishing to $bintrayRepo"
                new URI(bintrayRepo).toURL().openConnection().with {
                    doOutput = true
                    doInput = true
                    // Add basic authentication header.
                    def bintrayUsername = project.properties.bintrayUsername
                    def bintrayPassword = project.properties.bintrayPassword
                    setRequestProperty "Authorization", "Basic " + "$bintrayUsername:$bintrayPassword".getBytes().encodeBase64().toString()
                    requestMethod = "POST"
                    outputStream.flush()
                    outputStream.close()
                    project.logger.info inputStream.text
                    inputStream.close()

                    assert responseCode >= 200 && responseCode < 300
                }
            }
            else {
                project.logger.warn "artifacts may have been uploaded, but they have not been published"
            }
        }

        // Lazy initialisation of generic upload task
        project.gradle.taskGraph.whenReady { DefaultTaskGraphExecuter graph ->
            graph.allTasks.each { task ->
                if (task instanceof GenericBintrayUpload) {
                    project.tasks.withType(GenericBintrayUpload).each { GenericBintrayUpload uploadDist ->
                        verifyAndSetProperty(project, task, 'bintrayUsername')
                        verifyAndSetProperty(project, task, 'bintrayPassword')
                        verifyAndSetProperty(project, task, 'bintrayRepo')
                    }
                }
            }
        }
    }

    static void verifyAndSetProperty(Project proj, GenericBintrayUpload upload, String name) {
        if (upload."$name") {
            return
        }
        if (!proj.hasProperty(name)) {
            throw new GradleException("You must define the project property '$name'")
        }
        upload."$name" = proj."$name"
    }
}
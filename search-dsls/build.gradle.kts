import de.fayard.refreshVersions.core.versionFor

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

repositories {
    mavenCentral()
}

// publishing
apply(plugin = "maven-publish")
// apply(plugin = "org.jetbrains.dokka")

version = project.property("libraryVersion") as String
println("project: $path")
println("version: $version")
println("group: $group")

kotlin {
    jvm {
//        val main by compilations.getting {
//        }
//        val test by compilations.getting {
//            kotlinOptions {
//            }
//        }
    }
    js(BOTH) {
        nodejs {
            testTask {
                useMocha {
                    // javascript is a lot slower than Java, we hit the default timeout of 2000
                    timeout = "20000"
                }
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":json-dsl"))
                implementation(kotlin("stdlib-common", "_"))

            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common", "_"))
                implementation(kotlin("test-annotations-common", "_"))
                implementation(Testing.kotest.assertions.core)
            }
        }
        val jvmMain by existing {
            dependencies {
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5", "_"))
                implementation("ch.qos.logback:logback-classic:_")

                implementation(Testing.junit.jupiter.api)
                implementation(Testing.junit.jupiter.engine)
            }
        }
        val jsMain by existing {
            dependencies {
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js", "_"))
            }
        }

        all {
//            languageSettings.optIn("kotlin.RequiresOptIn")
        }

    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile> {
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "1.6"
    }
}


afterEvaluate {
//        val dokkaJar = tasks.register<Jar>("dokkaJar") {
//            from(tasks["dokkaHtml"])
//            dependsOn(tasks["dokkaHtml"])
//            archiveClassifier.set("javadoc")
//        }
//        val sourcesJar by tasks.registering(Jar::class) {
//            archiveClassifier.set("sources")
//            from(sourceSets["main"].allSource)
//            duplicatesStrategy = DuplicatesStrategy.INCLUDE
//        }

    configure<PublishingExtension> {
        repositories {
            logger.info("configuring publishing")
            if (project.hasProperty("publish")) {
                maven {
                    // this is what we do in github actions
                    // GOOGLE_APPLICATION_CREDENTIALS env var must be set for this to work
                    // either to a path with the json for the service account or with the base64 content of that.
                    // in github actions we should configure a secret on the repository with a base64 version of a service account
                    // export GOOGLE_APPLICATION_CREDENTIALS=$(cat /Users/jillesvangurp/.gcloud/jvg-admin.json | base64)

                    url = uri("gcs://mvn-tryformation/releases")

                    // FIXME figure out url & gcs credentials using token & actor
                    //     credentials()

                }
            }
        }
        publications.withType<MavenPublication> {
//                artifact(dokkaJar)
//                artifact(sourcesJar)
        }
    }
}
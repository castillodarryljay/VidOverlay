// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

tasks.register<Delete>("cleanApk") {
  delete("app-debug.apk")
}

tasks.register<Copy>("copyApk") {
  dependsOn("cleanApk")
  from("app/build/outputs/apk/debug/app-debug.apk")
  into(rootDir)
}

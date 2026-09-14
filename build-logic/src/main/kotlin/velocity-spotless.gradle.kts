import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin

apply<SpotlessPlugin>()

extensions.configure<SpotlessExtension> {
    java {
        if (project.name == "velocity-api") {
            licenseHeaderFile(file("HEADER.txt"))
            targetExclude("**/java/com/velocitypowered/api/util/Ordered.java")
        } else {
            licenseHeaderFile(rootProject.file("HEADER.txt"))
            // Our own code isn't upstream Velocity and doesn't carry their GPL header.
            targetExclude("**/java/net/sunnysmp/**")
        }
        //removeUnusedImports() // TODO: temp... somehow broke
    }
}

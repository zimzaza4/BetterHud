import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension

plugins {
    id("standard-conventions")
    id("com.vanniktech.maven.publish")
    signing
}

val publishName = "${rootProject.name}-${project.name}"

// 发 Maven Central 才需要 gpg 签名：只有显式 -Psign 时才走那条路。
// 平时发 Gitea Packages 不签名，也就不会再卡在 signMavenPublication（gpg）上。
val publishToCentral = project.hasProperty("sign")

// Gitea Packages（Maven registry）：与 CounterEmber 用同一套凭据 —— 环境变量 GITEA_TOKEN
// （CE 的 .env 里那个），以 "Authorization: token <值>" 头发送。没配就不注册这个仓库，
// 这样别人的机器 / CI 缺凭据时配置阶段也不会失败。
val giteaUrl = (findProperty("gitea.url") as String?)
    ?: "https://gitea.s4.smgoro.com/api/packages/EmberSurvival/maven"
val giteaToken = (findProperty("gitea.token") as String?) ?: System.getenv("GITEA_TOKEN")
val giteaReady = !giteaToken.isNullOrBlank()

dependencies {
    compileOnly(libs.bundles.adventure)

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testCompileOnly("org.projectlombok:lombok:1.18.46")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")
}

signing {
    if (publishToCentral) useGpgCmd()
}

val archiveName = "betterhud-${project.name}"

tasks.jar {
    archiveBaseName = archiveName
}

mavenPublishing {
    if (publishToCentral) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates("io.github.toxicity188", publishName, project.version as String)
    configure(JavaLibrary(
        javadocJar = JavadocJar.Javadoc(),
        sourcesJar = SourcesJar.Sources(),
    ))
    pom {
        name = publishName
        description = "A multi-platform server-side implementation of HUD in Minecraft, supporting Bukkit(with Folia), Velocity, and Fabric."
        inceptionYear = "2024"
        url = "https://github.com/toxicity188/BetterHud/"
        licenses {
            license {
                name = "MIT License"
                url = "https://mit-license.org/"
            }
        }
        developers {
            developer {
                id = "toxicity188"
                name = "toxicity188"
                url = "https://github.com/toxicity188/"
            }
        }
        scm {
            url = "https://github.com/toxicity188/BetterHud/"
            connection = "scm:git:git://github.com/toxicity188/BetterHud.git"
            developerConnection = "scm:git:ssh://git@github.com/toxicity188/BetterHud.git"
        }
    }
}

if (giteaReady) {
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "Gitea"
                url = uri(giteaUrl)
                isAllowInsecureProtocol = giteaUrl.startsWith("http://")
                credentials {
                    // Gitea 的 Maven registry 认证：用户名随便给（Gitea 只认令牌），密码=访问令牌。
                    // 注意不能用 HttpHeaderCredentials —— maven-publish 在发布时会调
                    // Credentials.getUserPrincipal()，那里是 null 直接 NPE（我们踩过一次）。
                    username = (findProperty("gitea.user") as String?) ?: "GITEA_TOKEN"
                    password = giteaToken
                }
            }
        }
    }
    logger.lifecycle("[BetterHud] Gitea Packages 已注册：$giteaUrl（发布任务 :api:publishAllPublicationsToGiteaRepository）")
} else {
    logger.lifecycle("[BetterHud] 未设置 GITEA_TOKEN（或用 -Pgitea.token），跳过 Gitea Packages 仓库")
}

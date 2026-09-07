plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Room 导出 schema，供迁移测试与版本审查使用（B20）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.example.zhishu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zhishu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // 本机物理内存紧张时，全量单测请用命令行限并行：
        // ./gradlew.bat testDebugUnitTest --max-workers=1
        unitTests.all { it.maxHeapSize = "1024m" }
        // 批次 B（B.0-7）：真实模型运行器 SampleRunner 不混入常规测试统计。
        // 只排除 SampleRunner 类（离线分析测试 SampleSecondaryAnalysisTest 仍参与常规回归）；
        // 真实调用走专门 task :app:runQualitySamples。
        unitTests.all { test ->
            if (test.name == "testDebugUnitTest") {
                test.exclude("**/samples/SampleRunner*")
            }
        }
    }

    sourceSets {
        getByName("test") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

/**
 * 批次 B：真实模型质量样本（B.0-7）。
 *
 * 用法：先设环境变量，再显式运行本 task——
 *   ZHISHU_SAMPLE_BASE_URL=... ZHISHU_SAMPLE_MODEL=... ZHISHU_SAMPLE_API_KEY=...
 *   ./gradlew.bat :app:runQualitySamples --max-workers=1
 *
 * 只有显式调用本 task 才可能产生真实模型费用；常规 testDebugUnitTest 排除 samples 包，
 * 且 SampleRunner 内部还有 ZHISHU_RUN_SAMPLES=true 双保险（有 Key 无开关也不运行）。
 */
val runQualitySamples by tasks.registering(Test::class) {
    description = "批次 B：真实模型质量样本（需 ZHISHU_SAMPLE_* 环境变量 + ZHISHU_RUN_SAMPLES=true）"
    group = "verification"
    // 复用 testDebugUnitTest 的类路径与输出（AGP 下直接引用 android sourceSets 在 kts 顶层不可靠）
    testClassesDirs = tasks.named("testDebugUnitTest", Test::class).get().testClassesDirs
    classpath = tasks.named("testDebugUnitTest", Test::class).get().classpath
    maxHeapSize = "1024m"
    filter { includeTestsMatching("com.example.zhishu.samples.*") }
    systemProperty("ZHISHU_RUN_SAMPLES", "true")
    // 与常规测试隔离：不并行、不增量缓存（每次样本都要真实调用）
    outputs.upToDateWhen { false }
}

/**
 * 端到端全流程验证（真实 API）：知识主干→圣经→章节→核验，走生产默认开关。
 * 用法与 runQualitySamples 相同（ZHISHU_SAMPLE_* 环境变量），只跑 E2eFullFlowRunner。
 */
val runE2eFullFlow by tasks.registering(Test::class) {
    description = "端到端全流程验证：知识主干→圣经→章节→核验（需 ZHISHU_SAMPLE_* 环境变量）"
    group = "verification"
    testClassesDirs = tasks.named("testDebugUnitTest", Test::class).get().testClassesDirs
    classpath = tasks.named("testDebugUnitTest", Test::class).get().classpath
    maxHeapSize = "2048m"
    filter { includeTestsMatching("com.example.zhishu.samples.E2eFullFlowRunner") }
    systemProperty("ZHISHU_RUN_SAMPLES", "true")
    outputs.upToDateWhen { false }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.pdfbox.android)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // 真实 WorkManager 调度行为测试（第二阶段的关键风险就在调度本身）
    testImplementation(libs.androidx.work.testing)
    // Compose UI 测试跑在 Robolectric 下，用于验证答题状态刷新（N1）
    testImplementation(libs.androidx.ui.test.junit4)
    // ui-test 的传递依赖版本本地缓存中不存在，提升到已缓存版本
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.espresso.core)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

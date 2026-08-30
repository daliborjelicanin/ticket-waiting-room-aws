plugins {
    id("java-library")
}

dependencies {
    // The only third-party dependency here, and it is present for one class:
    // SqsMessagePump. Two deployables consume SQS queues and both must have identical
    // at-least-once semantics — a delete that happens before the work, or a swallowed
    // handler exception, is a lost ticket in one service and a lost email in the other.
    // Duplicating that loop is how the two drift.
    //
    // compileOnly, NOT api, and the difference is not cosmetic. `common` is on the
    // classpath of both Lambda modules, which want it purely for the schema constants.
    // Exporting the SQS SDK from here put its default HTTP stacks — Apache 5 and Netty,
    // 12 jars — back into images that had spent slice 2 deliberately removing them, taking
    // hold-cleanup from 34 jars to 50 without a word. Consumers that actually use the pump
    // declare the SDK themselves; a consumer that forgets cannot compile, because
    // constructing a pump names SqsClient.
    compileOnly(platform(libs.aws.sdk.bom))
    compileOnly(libs.aws.sdk.sqs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

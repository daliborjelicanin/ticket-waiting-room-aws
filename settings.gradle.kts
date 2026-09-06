plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ticket-waiting-room"

include(
    "common",
    "waiting-room-service",
    "seat-inventory-service",
    "checkout-payment-service",
    "ticket-issuance-service",
    "notification-service",
    "hold-cleanup-lambda",
    "qr-generation-lambda",
    "queue-socket-lambda",
)

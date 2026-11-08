load("@build_bazel_rules_swift//swift:swift.bzl", "swift_library")

swift_library(
    name = "FamilyUI",
    module_name = "FamilyUI",
    srcs = glob(["Sources/FamilyUI/*.swift"]),
    deps = ["//family-core:FamilyCore", "//transport:FamilyTransport"],
    visibility = ["//visibility:public"],
)


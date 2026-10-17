load("@rules_java//java:defs.bzl", "java_library")

def fidelity_library(name, srcs, deps = []):
    java_library(
        name = name,
        srcs = srcs,
        deps = deps,
        visibility = ["//visibility:public"],
    )

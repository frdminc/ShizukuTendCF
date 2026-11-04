-keep class rikka.shizuku.shell.ShizukuShellLoader {
    public static void main(java.lang.String[]);
}

-allowaccessmodification

# Every class R8 renames goes into a subpackage of the loader's own package, never the default
# package. The manager APK's Shell runs in a class loader whose parent once held this dex, so
# default-package names here shadowed the manager's renamed classes (VerifyError, #626 / #28). The
# loader no longer takes that parent, and manager/rish-dex-check.gradle fails a release assemble
# when the two dex files still share a class name; this keeps their names apart all the same.
-repackageclasses rikka.shizuku.shell.r8

-keep class rikka.shizuku.shell.ShizukuShellLoader {
    public static void main(java.lang.String[]);
}

-allowaccessmodification
<<<<<<< HEAD

# Every class R8 renames goes into the loader's own package, not the default package. The app's
# R8 pass names its classes a, b, ... a0 in the default package too, and before #28 the loader
# handed its class loader to the app's code as the parent, so a shared name resolved to the
# loader's class there and ART rejected the app's (VerifyError). The loader no longer does that,
# and no longer bundles rikka.hidden.compat (whose keep rules lived here for #537/#544, VerifyErrors
# that may have been the same clash); this keeps the two dex files' names apart all the same.
-repackageclasses rikka.shizuku.shell
=======
# Not the default package: the manager APK's Shell runs in a class loader whose parent holds this
# dex, so default-package names here shadowed the manager's renamed classes (#626).
-repackageclasses rikka.shizuku.shell.r8
>>>>>>> upstream/master

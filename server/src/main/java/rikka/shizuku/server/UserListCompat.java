package rikka.shizuku.server;

import android.content.pm.UserInfo;
import android.os.Build;
import android.os.IUserManager;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import rikka.hidden.compat.util.SystemServiceBinder;
import rikka.shizuku.server.util.Logger;

/**
 * The list of users, whatever shape {@code IUserManager.getUsers} has on this Android (#27).
 *
 * <p>{@code dev.rikka.hidden:compat}'s {@code UserManagerApis.getUsers} calls the three-argument
 * {@code getUsers(excludePartial, excludeDying, excludePreCreated)} on every API 30+ device. Android
 * 17 QPR1 (AOSP tag android-17.0.0_r1, {@code core/java/android/os/IUserManager.aidl}) went back
 * to the one-argument {@code getUsers(excludeDying)}, so on it that call ends in a
 * {@link NoSuchMethodError} which {@code getUserIdsNoThrow} swallows, answering {@code [0]}. The
 * server then pushes its binder to user 0 only, and apps in work profiles and secondary users never
 * get one.
 *
 * <p>This tries the form this API level is documented with first, the other second, and as a last
 * resort any {@code getUsers} the running framework declares (reflection, for a shape neither stub
 * knows yet). The form that worked is remembered. Older Android keeps the call it always made.
 */
public final class UserListCompat {

    private static final Logger LOGGER = new Logger("UserListCompat");

    // The same lookup compat's Services makes: in the manager the binder goes through the
    // ShizukuBinderWrapper listener ShizukuSystemApis installs, in the server it is direct.
    private static final SystemServiceBinder<IUserManager> USER_MANAGER =
            new SystemServiceBinder<>("user", IUserManager.Stub::asInterface);

    static final int UNKNOWN = 0;
    static final int THREE_ARGS = 1;
    static final int ONE_ARG = 2;
    static final int REFLECTIVE = 3;

    // Which getUsers the running framework has; UNKNOWN until the first call finds out.
    private static volatile int variant = UNKNOWN;
    private static volatile Method reflective;
    private static volatile boolean failureLogged;

    /** {@link Build.VERSION#SDK_INT}, a field so tests can stand in another Android. */
    @VisibleForTesting
    static int sdkInt = Build.VERSION.SDK_INT;

    private UserListCompat() {
    }

    @NonNull
    public static List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            throws RemoteException {
        IUserManager um = USER_MANAGER.get();
        if (um == null) {
            throw new RemoteException("user service is not available");
        }
        return getUsers(um, excludePartial, excludeDying, excludePreCreated);
    }

    @NonNull
    public static List<UserInfo> getUsersNoThrow(boolean excludePartial, boolean excludeDying, boolean excludePreCreated) {
        try {
            return getUsers(excludePartial, excludeDying, excludePreCreated);
        } catch (Throwable tr) {
            logFailure(tr);
            return new ArrayList<>();
        }
    }

    /** Every user's id; {@code [0]} when the list cannot be read at all (as compat answered). */
    @NonNull
    public static Collection<Integer> getUserIdsNoThrow() {
        return getUserIdsNoThrow(true, true, true);
    }

    @NonNull
    public static Collection<Integer> getUserIdsNoThrow(boolean excludePartial, boolean excludeDying, boolean excludePreCreated) {
        IUserManager um;
        try {
            um = USER_MANAGER.get();
        } catch (Throwable tr) {
            um = null;
        }
        return getUserIdsNoThrow(um, excludePartial, excludeDying, excludePreCreated);
    }

    @VisibleForTesting
    @NonNull
    static Collection<Integer> getUserIdsNoThrow(IUserManager um, boolean excludePartial, boolean excludeDying, boolean excludePreCreated) {
        // A plain JDK set: android.util.ArraySet is a no-op stub in JVM unit tests.
        Set<Integer> result = new LinkedHashSet<>();
        try {
            if (um == null) {
                throw new RemoteException("user service is not available");
            }
            for (UserInfo it : getUsers(um, excludePartial, excludeDying, excludePreCreated)) {
                result.add(it.id);
            }
        } catch (Throwable tr) {
            logFailure(tr);
            result.add(0);
        }
        return result;
    }

    @VisibleForTesting
    @NonNull
    static List<UserInfo> getUsers(@NonNull IUserManager um, boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            throws RemoteException {
        int known = variant;
        if (known != UNKNOWN) {
            return call(um, known, excludePartial, excludeDying, excludePreCreated);
        }

        // The form this API level is documented with first: API 30 to 16 have three arguments,
        // Android 17 QPR1 and everything before API 30 have one.
        int expected = sdkInt >= 30 && sdkInt < 37 ? THREE_ARGS : ONE_ARG;
        int other = expected == THREE_ARGS ? ONE_ARG : THREE_ARGS;
        IncompatibleClassChangeError missing = null;
        for (int candidate : new int[]{expected, other}) {
            try {
                List<UserInfo> list = call(um, candidate, excludePartial, excludeDying, excludePreCreated);
                variant = candidate;
                if (candidate != expected) {
                    LOGGER.w("IUserManager.getUsers has the %s form on API %d; using it",
                            candidate == ONE_ARG ? "one-argument" : "three-argument", sdkInt);
                }
                return list;
            } catch (IncompatibleClassChangeError e) {
                // NoSuchMethodError or AbstractMethodError: this framework has no such method.
                missing = e;
            }
        }

        // Neither shape the stub knows: whatever getUsers the framework declares, with booleans.
        Method found = findReflective(um);
        if (found != null) {
            List<UserInfo> list = invoke(um, found, excludePartial, excludeDying, excludePreCreated);
            reflective = found;
            variant = REFLECTIVE;
            LOGGER.w("IUserManager.getUsers has an unknown form on API %d (%s); using it reflectively", sdkInt, found);
            return list;
        }
        throw missing != null ? missing : new NoSuchMethodError("IUserManager.getUsers on API " + sdkInt);
    }

    private static List<UserInfo> call(IUserManager um, int form, boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            throws RemoteException {
        List<UserInfo> list;
        switch (form) {
            case THREE_ARGS:
                list = um.getUsers(excludePartial, excludeDying, excludePreCreated);
                break;
            case ONE_ARG:
                // This form cannot exclude partial or pre-created users itself.
                list = withoutExcluded(um.getUsers(excludeDying), excludePartial, excludePreCreated);
                break;
            case REFLECTIVE:
                Method m = reflective;
                if (m == null) {
                    throw new NoSuchMethodError("IUserManager.getUsers (reflective form lost)");
                }
                list = invoke(um, m, excludePartial, excludeDying, excludePreCreated);
                break;
            default:
                throw new IllegalStateException("unknown form " + form);
        }
        return list != null ? list : Collections.<UserInfo>emptyList();
    }

    /** A {@code getUsers} taking only booleans, the fewest parameters first. */
    private static Method findReflective(IUserManager um) {
        Method best = null;
        for (Method m : um.getClass().getMethods()) {
            if (!"getUsers".equals(m.getName()) || !List.class.isAssignableFrom(m.getReturnType())) continue;
            boolean allBooleans = true;
            for (Class<?> p : m.getParameterTypes()) {
                if (p != boolean.class) {
                    allBooleans = false;
                    break;
                }
            }
            if (!allBooleans) continue;
            if (best == null || m.getParameterTypes().length < best.getParameterTypes().length) best = m;
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static List<UserInfo> invoke(IUserManager um, Method m, boolean excludePartial, boolean excludeDying, boolean excludePreCreated)
            throws RemoteException {
        int n = m.getParameterTypes().length;
        Object[] args = new Object[n];
        for (int i = 0; i < n; i++) args[i] = true;
        if (n == 1) {
            args[0] = excludeDying;
        } else if (n >= 3) {
            args[0] = excludePartial;
            args[1] = excludeDying;
            args[2] = excludePreCreated;
        }
        try {
            Object result = m.invoke(um, args);
            List<UserInfo> list = result != null ? (List<UserInfo>) result : Collections.<UserInfo>emptyList();
            // Fewer than three arguments: partial and pre-created users were not excluded.
            return n < 3 ? withoutExcluded(list, excludePartial, excludePreCreated) : list;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteException) throw (RemoteException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new NoSuchMethodError("IUserManager.getUsers is not accessible: " + e);
        }
    }

    /**
     * The list without partial and pre-created users, when the caller asked to exclude them and the
     * form called could not. {@code UserInfo.partial} and {@code UserInfo.preCreated} are public
     * fields on API 30+ but not in the hidden-api stub, so they are read reflectively; a field this
     * Android lacks counts as false.
     */
    @VisibleForTesting
    @NonNull
    static List<UserInfo> withoutExcluded(List<UserInfo> list, boolean excludePartial, boolean excludePreCreated) {
        if (list == null) return Collections.emptyList();
        if (!excludePartial && !excludePreCreated) return list;
        List<UserInfo> kept = new ArrayList<>(list.size());
        for (UserInfo ui : list) {
            if (ui == null) continue;
            if (excludePartial && booleanField(ui, "partial")) continue;
            if (excludePreCreated && booleanField(ui, "preCreated")) continue;
            kept.add(ui);
        }
        return kept;
    }

    private static boolean booleanField(UserInfo ui, String name) {
        try {
            Field f = ui.getClass().getField(name);
            return f.getType() == boolean.class && f.getBoolean(ui);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void logFailure(Throwable tr) {
        // Once: this runs on every binder push and every grant reconcile.
        if (failureLogged) return;
        failureLogged = true;
        LOGGER.e(tr, "cannot list users on API %d; only user 0 will be served", sdkInt);
    }

    @VisibleForTesting
    static void resetForTesting() {
        variant = UNKNOWN;
        reflective = null;
        failureLogged = false;
        sdkInt = Build.VERSION.SDK_INT;
    }
}

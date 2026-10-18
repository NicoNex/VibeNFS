// Thin JNI bridge over the libnfs sync API. Every context runs libnfs' own
// service thread, so all calls below are safe from any Java thread.
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include <nfsc/libnfs.h>
#include <nfsc/libnfs-raw.h>
#include <libnfs-raw-mount.h> // struct exportnode

static jclass entryClass, stringClass;
static jmethodID entryCtor;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = (*env)->FindClass(env, "dev/vibenfs/nfs/NfsEntry");
    if (!local) return JNI_ERR;
    entryClass = (*env)->NewGlobalRef(env, local);
    stringClass = (*env)->NewGlobalRef(env, (*env)->FindClass(env, "java/lang/String"));
    entryCtor = (*env)->GetMethodID(env, entryClass, "<init>", "(Ljava/lang/String;ZJJ)V");
    return entryCtor ? JNI_VERSION_1_6 : JNI_ERR;
}

#define CTX ((struct nfs_context *) (intptr_t) ctx)
#define IS_DOT(n) (!strcmp(n, ".") || !strcmp(n, ".."))
#define FH ((struct nfsfh *) (intptr_t) fh)

static void throwIo(JNIEnv *env, struct nfs_context *nfs, int err, const char *what) {
    const char *msg = nfs ? nfs_get_error(nfs) : NULL;
    char buf[512];
    snprintf(buf, sizeof buf, "%s: %s", what, msg && *msg ? msg : strerror(-err));
    const char *type = err == -ENOENT ? "java/io/FileNotFoundException"
                     : err == -EEXIST ? "java/nio/file/FileAlreadyExistsException"
                     : "java/io/IOException";
    jclass cls = (*env)->FindClass(env, type);
    (*env)->ThrowNew(env, cls, buf);
}

/** Runs `call` with a UTF-8 copy of `jpath` bound to `path`, throwing on negative result. */
#define WITH_PATH(jpath, what, call)                                     \
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);      \
    int rc = (call);                                                     \
    (*env)->ReleaseStringUTFChars(env, jpath, path);                     \
    if (rc < 0) throwIo(env, CTX, rc, what);

static int checkBounds(JNIEnv *env, jbyteArray buf, jint size) {
    if (size >= 0 && size <= (*env)->GetArrayLength(env, buf)) return 1;
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IndexOutOfBoundsException"), "size outside buffer");
    return 0;
}

static jobject newEntry(JNIEnv *env, const char *name, int dir, uint64_t size, uint64_t mtimeMs) {
    jstring jname = (*env)->NewStringUTF(env, name);
    jobject entry = (*env)->NewObject(env, entryClass, entryCtor, jname, (jboolean) dir, (jlong) size, (jlong) mtimeMs);
    (*env)->DeleteLocalRef(env, jname);
    return entry;
}

JNIEXPORT jlong JNICALL
Java_dev_vibenfs_nfs_NfsClient_nMount(JNIEnv *env, jclass clazz, jstring jhost, jstring jexport,
                                      jint version, jint port, jboolean traverse, jint uid, jint gid, jint timeoutMs) {
    struct nfs_context *nfs = nfs_init_context();
    if (!nfs) {
        throwIo(env, NULL, -ENOMEM, "init");
        return 0;
    }
    nfs_set_timeout(nfs, timeoutMs);
    nfs_set_autoreconnect(nfs, 3); // bounded, so a vanished server fails calls instead of hanging them
    if (port > 0) {
        // Non-standard port: skip portmapper, serve MOUNT and NFS on the same port.
        nfs_set_nfsport(nfs, port);
        nfs_set_mountport(nfs, port);
    }
    // Nested-export traversal needs MOUNT EXPORT, which some userspace servers lack.
    nfs_set_auto_traverse_mounts(nfs, traverse);
    if (uid >= 0) nfs_set_uid(nfs, uid);
    if (gid >= 0) nfs_set_gid(nfs, gid);
    int rc = nfs_set_version(nfs, version);
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    const char *export = (*env)->GetStringUTFChars(env, jexport, NULL);
    if (rc == 0) rc = nfs_mount(nfs, host, export);
    (*env)->ReleaseStringUTFChars(env, jhost, host);
    (*env)->ReleaseStringUTFChars(env, jexport, export);
    if (rc == 0 && nfs_mt_service_thread_start(nfs) != 0) {
        nfs_umount(nfs);
        rc = -EAGAIN;
    }
    if (rc != 0) {
        throwIo(env, nfs, rc, "mount");
        nfs_destroy_context(nfs);
        return 0;
    }
    return (jlong) (intptr_t) nfs;
}

JNIEXPORT void JNICALL
Java_dev_vibenfs_nfs_NfsClient_nUmount(JNIEnv *env, jclass clazz, jlong ctx) {
    nfs_mt_service_thread_stop(CTX);
    nfs_umount(CTX);
    nfs_destroy_context(CTX);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_vibenfs_nfs_NfsClient_nExports(JNIEnv *env, jclass clazz, jstring jhost, jint port) {
    const char *host = (*env)->GetStringUTFChars(env, jhost, NULL);
    struct exportnode *list = port > 0 ? mount_getexports_mountport(host, port) : mount_getexports_timeout(host, 5000);
    (*env)->ReleaseStringUTFChars(env, jhost, host);
    int n = 0;
    for (struct exportnode *e = list; e; e = e->ex_next) n++;
    jobjectArray out = (*env)->NewObjectArray(env, n, stringClass, NULL);
    int i = 0;
    for (struct exportnode *e = list; e; e = e->ex_next) {
        jstring s = (*env)->NewStringUTF(env, e->ex_dir);
        (*env)->SetObjectArrayElement(env, out, i++, s);
        (*env)->DeleteLocalRef(env, s);
    }
    if (list) mount_free_export_list(list);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_vibenfs_nfs_NfsClient_nList(JNIEnv *env, jclass clazz, jlong ctx, jstring jpath) {
    struct nfsdir *dir;
    WITH_PATH(jpath, "opendir", nfs_opendir(CTX, path, &dir))
    if (rc < 0) return NULL;
    // Entries stay owned by `dir` until closedir, so collect pointers in one pass.
    int n = 0, cap = 64;
    struct nfsdirent **all = malloc(cap * sizeof *all), *e;
    while (all && (e = nfs_readdir(CTX, dir))) {
        if (IS_DOT(e->name)) continue;
        if (n == cap) {
            struct nfsdirent **grown = realloc(all, (cap *= 2) * sizeof *all);
            if (!grown) break;
            all = grown;
        }
        all[n++] = e;
    }
    jobjectArray out = (*env)->NewObjectArray(env, n, entryClass, NULL);
    for (int i = 0; i < n; i++) {
        e = all[i];
        jobject entry = newEntry(env, e->name, (e->mode & S_IFMT) == S_IFDIR, e->size,
                                 e->mtime.tv_sec * 1000ULL + e->mtime.tv_usec / 1000);
        (*env)->SetObjectArrayElement(env, out, i, entry);
        (*env)->DeleteLocalRef(env, entry);
    }
    free(all);
    nfs_closedir(CTX, dir);
    return out;
}

JNIEXPORT jobject JNICALL
Java_dev_vibenfs_nfs_NfsClient_nStat(JNIEnv *env, jclass clazz, jlong ctx, jstring jpath) {
    struct nfs_stat_64 st;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int rc = nfs_stat64(CTX, path, &st);
    jobject entry = NULL;
    if (rc < 0) {
        throwIo(env, CTX, rc, "stat");
    } else {
        const char *slash = strrchr(path, '/');
        entry = newEntry(env, slash && slash[1] ? slash + 1 : path, (st.nfs_mode & S_IFMT) == S_IFDIR,
                         st.nfs_size, st.nfs_mtime * 1000 + st.nfs_mtime_nsec / 1000000);
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return entry;
}

JNIEXPORT jlongArray JNICALL
Java_dev_vibenfs_nfs_NfsClient_nStatVfs(JNIEnv *env, jclass clazz, jlong ctx) {
    struct nfs_statvfs_64 sv;
    int rc = nfs_statvfs64(CTX, "/", &sv);
    if (rc < 0) {
        throwIo(env, CTX, rc, "statvfs");
        return NULL;
    }
    jlong vals[] = {(jlong) (sv.f_blocks * sv.f_frsize), (jlong) (sv.f_bavail * sv.f_frsize)};
    jlongArray out = (*env)->NewLongArray(env, 2);
    (*env)->SetLongArrayRegion(env, out, 0, 2, vals);
    return out;
}

JNIEXPORT jlong JNICALL
Java_dev_vibenfs_nfs_NfsClient_nOpen(JNIEnv *env, jclass clazz, jlong ctx, jstring jpath, jint flags) {
    struct nfsfh *fh = NULL;
    WITH_PATH(jpath, "open", nfs_open2(CTX, path, flags, 0644, &fh))
    return (jlong) (intptr_t) fh;
}

JNIEXPORT jint JNICALL
Java_dev_vibenfs_nfs_NfsClient_nPread(JNIEnv *env, jclass clazz, jlong ctx, jlong fh,
                                      jbyteArray jbuf, jint size, jlong offset) {
    if (!checkBounds(env, jbuf, size)) return -1;
    // ponytail: malloc+copy per call (read and write); pinning the array would stall GC for the whole network round trip.
    void *buf = malloc(size);
    if (!buf) {
        throwIo(env, NULL, -ENOMEM, "read");
        return -1;
    }
    int rc = nfs_pread(CTX, FH, buf, size, offset);
    if (rc > 0) (*env)->SetByteArrayRegion(env, jbuf, 0, rc, buf);
    free(buf);
    if (rc < 0) throwIo(env, CTX, rc, "read");
    return rc;
}

JNIEXPORT jint JNICALL
Java_dev_vibenfs_nfs_NfsClient_nPwrite(JNIEnv *env, jclass clazz, jlong ctx, jlong fh,
                                       jbyteArray jbuf, jint size, jlong offset) {
    if (!checkBounds(env, jbuf, size)) return -1;
    jbyte *buf = malloc(size);
    if (!buf) {
        throwIo(env, NULL, -ENOMEM, "write");
        return -1;
    }
    (*env)->GetByteArrayRegion(env, jbuf, 0, size, buf);
    int rc = nfs_pwrite(CTX, FH, buf, size, offset);
    free(buf);
    if (rc < 0) throwIo(env, CTX, rc, "write");
    return rc;
}

JNIEXPORT void JNICALL
Java_dev_vibenfs_nfs_NfsClient_nClose(JNIEnv *env, jclass clazz, jlong ctx, jlong fh) {
    int rc = nfs_close(CTX, FH);
    if (rc < 0) throwIo(env, CTX, rc, "close");
}

JNIEXPORT void JNICALL
Java_dev_vibenfs_nfs_NfsClient_nMkdir(JNIEnv *env, jclass clazz, jlong ctx, jstring jpath) {
    WITH_PATH(jpath, "mkdir", nfs_mkdir(CTX, path))
}

JNIEXPORT void JNICALL
Java_dev_vibenfs_nfs_NfsClient_nDelete(JNIEnv *env, jclass clazz, jlong ctx, jstring jpath, jboolean isDir) {
    WITH_PATH(jpath, "delete", isDir ? nfs_rmdir(CTX, path) : nfs_unlink(CTX, path))
}

JNIEXPORT void JNICALL
Java_dev_vibenfs_nfs_NfsClient_nRename(JNIEnv *env, jclass clazz, jlong ctx, jstring jfrom, jstring jto) {
    const char *to = (*env)->GetStringUTFChars(env, jto, NULL);
    WITH_PATH(jfrom, "rename", nfs_rename(CTX, path, to))
    (*env)->ReleaseStringUTFChars(env, jto, to);
}

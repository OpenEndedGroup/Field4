// field-fdpass: a tiny JNI shim so the JVM can hand a raw file descriptor to another process via
// SCM_RIGHTS over a Unix domain socket.
//
// Why this exists: the Linux/NVENC sender seam (see dev_docs/vision_frontend.md, and
// native/field-encode-linux/WIRE_FORMAT-addendum) needs to move a Vulkan-exported
// VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT fd from this JVM process into the field-encode-linux
// helper process. The first design tried duplicating it via /proc/<pid>/fd/<fd> (which works for
// anonymous-inode files — pipes, eventfds, real dma-bufs) but NVIDIA's opaque-fd export turned out
// to be backed by /dev/nvidiactl (a real device-special file): reopening that path via the procfs
// magic symlink performs a *fresh* open() against the nvidia driver, producing an fd that is
// numerically valid but semantically unrelated to the original memory object — cuImportExternalMemory
// then fails with CUDA_ERROR_UNKNOWN (999). SCM_RIGHTS is the only mechanism that duplicates the
// actual kernel `struct file*` regardless of what's behind it, so it's the correct fix, not a
// workaround. Java has no standard-library support for sendmsg()+ancillary data, hence this shim.
//
// One JNI method, one job: connect to a listening AF_UNIX SOCK_STREAM socket at `socketPath` and
// send every fd in `fds` in a single sendmsg() call (one SCM_RIGHTS control message carrying all of
// them, in array order) plus a 1-byte dummy payload (sendmsg requires at least one byte of real
// data alongside ancillary data on Linux). Returns true on success.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

// Symbol name matches fieldfdpass.UnixFdPassNative (java/fieldfdpass/UnixFdPassNative.java), a
// standalone class deliberately kept off Field4's main jvmMain classpath — see that file's
// comment and the "field4-jni-repl-bug" memory note for why.
JNIEXPORT jboolean JNICALL Java_fieldfdpass_UnixFdPassNative_sendFds(
    JNIEnv* env, jclass clazz, jstring socketPath, jintArray fds) {

    const char* path = (*env)->GetStringUTFChars(env, socketPath, NULL);
    if (!path) return JNI_FALSE;

    jsize count = (*env)->GetArrayLength(env, fds);
    jint* fdValues = (*env)->GetIntArrayElements(env, fds, NULL);
    jboolean ok = JNI_FALSE;

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock >= 0) {
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);

        if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) == 0) {
            char dummy = 'F';
            struct iovec iov = { .iov_base = &dummy, .iov_len = 1 };

            size_t cmsgSpace = CMSG_SPACE(sizeof(int) * (size_t)count);
            char* cmsgBuf = (char*)malloc(cmsgSpace);
            if (cmsgBuf) {
                memset(cmsgBuf, 0, cmsgSpace);
                struct msghdr msg;
                memset(&msg, 0, sizeof(msg));
                msg.msg_iov = &iov;
                msg.msg_iovlen = 1;
                msg.msg_control = cmsgBuf;
                msg.msg_controllen = cmsgSpace;

                struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
                cmsg->cmsg_level = SOL_SOCKET;
                cmsg->cmsg_type = SCM_RIGHTS;
                cmsg->cmsg_len = CMSG_LEN(sizeof(int) * (size_t)count);
                memcpy(CMSG_DATA(cmsg), fdValues, sizeof(int) * (size_t)count);
                msg.msg_controllen = cmsg->cmsg_len;

                if (sendmsg(sock, &msg, 0) >= 0) ok = JNI_TRUE;
                free(cmsgBuf);
            }
        }
        close(sock);
    }

    (*env)->ReleaseIntArrayElements(env, fds, fdValues, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, socketPath, path);
    return ok;
}

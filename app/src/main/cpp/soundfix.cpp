/**
 * Wallpaper Engine 场景壁纸声音修复 — native 实现。
 *
 * libscenejni.so 的 AndroidMediaExtensions 音频函数全部是空壳 stub
 * （CreateSoundBuffer/CreateSound 返回空，Play/Stop/SetVolume 为空操作）。
 *
 * 关键约束：Android linker namespace 隔离 —— libscenejni.so 加载在目标应用的
 * classloader namespace (clns-9)，而模块的 libsoundfix.so 在隔离 namespace (clns-10)，
 * dlopen/dlsym 互相不可见。因此本实现不依赖动态链接器：
 *
 *   1. 从 /proc/self/maps 定位 libscenejni.so 的加载基址
 *   2. 直接从进程内存解析 ELF64 头 / program headers / dynamic 段 / dynsym
 *   3. 找到 _ZTV22AndroidMediaExtensions 虚表和 13 个音频函数的运行时地址
 *   4. 校验虚表槽位与函数地址一致（防版本布局变化）
 *   5. mprotect + 替换虚表槽位，桥接到 Java SoundBridge
 *
 * 虚表槽位布局（_ZTV[2+N] = 第 N 个虚函数）：
 *   16 CreateSoundBuffer    22 IsPaused
 *   17 DestroySoundBuffer   23 IsStopped
 *   18 CreateSound          24 Play
 *   19 DestroySound         25 Pause
 *   20 GetDuration          26 Stop
 *   21 IsPlaying            27 SetVolume
 *                         28 GetChannelCount
 */
#include <jni.h>
#include <elf.h>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#define TAG "WESoundFix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_bridge = nullptr;   // SoundBridge 全局引用
static jmethodID g_create, g_play, g_pause, g_stop, g_setVol, g_getDur,
                 g_isPlaying, g_isPaused, g_isStopped, g_destroy;

/** CreateSoundBuffer 提供的音频数据，CreateSound 消费。 */
struct SoundBuffer {
    std::vector<uint8_t> data;
};

static JNIEnv* getEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args{JNI_VERSION_1_6, const_cast<char*>("WESoundFix"), nullptr};
    g_vm->AttachCurrentThread(&env, &args);
    return env;
}

static jlong asId(void* sound) { return static_cast<jlong>(reinterpret_cast<intptr_t>(sound)); }

// ==================== 替换的虚函数实现 ====================

// data==null → 缓存查询，返回 null 让引擎走 pkg 数据加载路径
// data!=null → 实际音频数据，存储并返回
static void* hf_createSoundBuffer(void* /*self*/, const char* path,
                                   const uint8_t* data, uint32_t size) {
    if (data == nullptr || size == 0) return nullptr;
    auto* buf = new SoundBuffer();
    buf->data.assign(data, data + size);
    LOGI("CreateSoundBuffer: %s (%u bytes)", path ? path : "(null)", size);
    return buf;
}

static void hf_destroySoundBuffer(void* /*self*/, void* buffer) {
    delete static_cast<SoundBuffer*>(buffer);
}

static void* hf_createSound(void* /*self*/, void* buffer) {
    auto* buf = static_cast<SoundBuffer*>(buffer);
    if (buf == nullptr || buf->data.empty()) return nullptr;
    JNIEnv* env = getEnv();
    if (env == nullptr) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(buf->data.size()));
    if (arr == nullptr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(buf->data.size()),
                            reinterpret_cast<const jbyte*>(buf->data.data()));
    jlong id = env->CallLongMethod(g_bridge, g_create, arr);
    env->DeleteLocalRef(arr);
    LOGI("CreateSound -> id %lld", static_cast<long long>(id));
    return reinterpret_cast<void*>(static_cast<intptr_t>(id));
}

static void hf_destroySound(void* /*self*/, void* sound) {
    if (sound == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    env->CallVoidMethod(g_bridge, g_destroy, asId(sound));
}

static double hf_getDuration(void* /*self*/, void* sound) {
    if (sound == nullptr) return 0.0;
    JNIEnv* env = getEnv();
    if (env == nullptr) return 0.0;
    return env->CallDoubleMethod(g_bridge, g_getDur, asId(sound));
}

static int64_t hf_isPlaying(void* /*self*/, void* sound) {
    if (sound == nullptr) return 0;
    JNIEnv* env = getEnv();
    if (env == nullptr) return 0;
    return env->CallBooleanMethod(g_bridge, g_isPlaying, asId(sound)) ? 1 : 0;
}

static int64_t hf_isPaused(void* /*self*/, void* sound) {
    if (sound == nullptr) return 0;
    JNIEnv* env = getEnv();
    if (env == nullptr) return 0;
    return env->CallBooleanMethod(g_bridge, g_isPaused, asId(sound)) ? 1 : 0;
}

static int64_t hf_isStopped(void* /*self*/, void* sound) {
    if (sound == nullptr) return 1;
    JNIEnv* env = getEnv();
    if (env == nullptr) return 1;
    return env->CallBooleanMethod(g_bridge, g_isStopped, asId(sound)) ? 1 : 0;
}

static void hf_play(void* /*self*/, void* sound, bool /*flag*/) {
    if (sound == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    env->CallVoidMethod(g_bridge, g_play, asId(sound));
}

static void hf_pause(void* /*self*/, void* sound) {
    if (sound == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    env->CallVoidMethod(g_bridge, g_pause, asId(sound));
}

static void hf_stop(void* /*self*/, void* sound) {
    if (sound == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    env->CallVoidMethod(g_bridge, g_stop, asId(sound));
}

static void hf_setVolume(void* /*self*/, void* sound, float vol) {
    if (sound == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    env->CallVoidMethod(g_bridge, g_setVol, asId(sound), static_cast<jfloat>(vol));
}

static int64_t hf_getChannelCount(void* /*self*/, void* /*sound*/) {
    return 2;
}

// ==================== 内存 ELF 解析（绕过 linker namespace） ====================

struct MemElf {
    uintptr_t base;
    Elf64_Sym* symtab;
    const char* strtab;
    size_t nsyms;
};

static bool parseLoadedElf(uintptr_t base, MemElf* out);
static const Elf64_Sym* findMemSymbol(const MemElf* elf, const char* name);

/** 在 /proc/self/maps 中定位 libscenejni.so 的加载基址。
 *
 * 注意：目标应用 extractNativeLibs=false，libscenejni.so 从 APK 内直接 mmap，
 * maps 中路径显示为 ".../base.apk"（不含库名）。因此遍历所有 APK/库映射，
 * 逐个解析 ELF 并查找 _ZTV22AndroidMediaExtensions 符号确认身份。
 */
static bool findSceneJniBase(uintptr_t* baseOut) {
    FILE* f = fopen("/proc/self/maps", "r");
    if (f == nullptr) {
        LOGE("open /proc/self/maps failed");
        return false;
    }
    char line[1024];
    bool found = false;
    int checked = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        // 匹配两类路径：.apk（APK 内加载）或 libscenejni（独立安装）
        if (strstr(line, ".apk") == nullptr && strstr(line, "libscenejni") == nullptr) {
            continue;
        }
        uintptr_t start = strtoull(line, nullptr, 16);
        if (start == 0) continue;
        // 只有 .so 的第一段映射以 ELF 头开始
        if (memcmp(reinterpret_cast<void*>(start), ELFMAG, SELFMAG) != 0) continue;
        checked++;
        // 解析并确认包含目标虚表符号（APK 内可能有多个 .so）
        MemElf elf;
        if (!parseLoadedElf(start, &elf)) continue;
        if (findMemSymbol(&elf, "_ZTV22AndroidMediaExtensions") != nullptr) {
            *baseOut = start;
            found = true;
            break;
        }
    }
    fclose(f);
    LOGI("findSceneJniBase: checked %d ELF mappings, found=%d", checked, found ? 1 : 0);
    return found;
}

/** 解析已加载进内存的 ELF（dynsym/dynstr 位于只读 PT_LOAD，直接可读）。
 *  含严格结构校验：APK 内资源可能碰巧以 ELF magic 开头，全部检查通过才解析。 */
static bool parseLoadedElf(uintptr_t base, MemElf* out) {
    auto* ehdr = reinterpret_cast<Elf64_Ehdr*>(base);
    if (ehdr->e_ident[EI_CLASS] != ELFCLASS64) return false;
    if (ehdr->e_ident[EI_DATA] != ELFDATA2LSB) return false;
    if (ehdr->e_type != ET_DYN) return false;
    if (ehdr->e_machine != EM_AARCH64) return false;
    if (ehdr->e_phentsize != sizeof(Elf64_Phdr)) return false;
    if (ehdr->e_phnum == 0 || ehdr->e_phnum > 256) return false;

    auto* phdrs = reinterpret_cast<Elf64_Phdr*>(base + ehdr->e_phoff);
    Elf64_Dyn* dyn = nullptr;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<Elf64_Dyn*>(base + phdrs[i].p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) return false;

    // bionic linker 不回写 dynamic 段的 SYMTAB/STRTAB 值，仍是相对基址的偏移
    uintptr_t symtabOff = 0, strtabOff = 0;
    for (Elf64_Dyn* d = dyn; d->d_tag != DT_NULL; d++) {
        if (d->d_tag == DT_SYMTAB) symtabOff = d->d_un.d_ptr;
        else if (d->d_tag == DT_STRTAB) strtabOff = d->d_un.d_ptr;
    }
    if (symtabOff == 0 || strtabOff <= symtabOff) return false;
    // 符号表大小合理性（dynsym 紧邻 dynstr，差值即表大小）
    size_t nsyms = (strtabOff - symtabOff) / sizeof(Elf64_Sym);
    if (nsyms == 0 || nsyms > 1000000) return false;

    out->base = base;
    out->symtab = reinterpret_cast<Elf64_Sym*>(base + symtabOff);
    out->strtab = reinterpret_cast<const char*>(base + strtabOff);
    out->nsyms = nsyms;
    return true;
}

static const Elf64_Sym* findMemSymbol(const MemElf* elf, const char* name) {
    for (size_t i = 0; i < elf->nsyms; i++) {
        const Elf64_Sym* s = &elf->symtab[i];
        if (s->st_name == 0 || s->st_value == 0) continue;
        if (strcmp(elf->strtab + s->st_name, name) == 0) return s;
    }
    return nullptr;
}

// ==================== 虚表替换 ====================

struct SlotPatch {
    int slot;
    const char* symbol;
    void* replacement;
};

static const SlotPatch kPatches[] = {
    {16, "_ZN22AndroidMediaExtensions17CreateSoundBufferEPKcPKhj", (void*)hf_createSoundBuffer},
    {17, "_ZN22AndroidMediaExtensions18DestroySoundBufferEPv",     (void*)hf_destroySoundBuffer},
    {18, "_ZN22AndroidMediaExtensions11CreateSoundEPv",            (void*)hf_createSound},
    {19, "_ZN22AndroidMediaExtensions12DestroySoundEPv",           (void*)hf_destroySound},
    {20, "_ZN22AndroidMediaExtensions11GetDurationEPv",            (void*)hf_getDuration},
    {21, "_ZN22AndroidMediaExtensions9IsPlayingEPv",               (void*)hf_isPlaying},
    {22, "_ZN22AndroidMediaExtensions8IsPausedEPv",                (void*)hf_isPaused},
    {23, "_ZN22AndroidMediaExtensions9IsStoppedEPv",               (void*)hf_isStopped},
    {24, "_ZN22AndroidMediaExtensions4PlayEPvb",                   (void*)hf_play},
    {25, "_ZN22AndroidMediaExtensions5PauseEPv",                   (void*)hf_pause},
    {26, "_ZN22AndroidMediaExtensions4StopEPv",                    (void*)hf_stop},
    {27, "_ZN22AndroidMediaExtensions9SetVolumeEPvf",              (void*)hf_setVolume},
    {28, "_ZN22AndroidMediaExtensions15GetChannelCountEPv",        (void*)hf_getChannelCount},
};
static const size_t kPatchCount = sizeof(kPatches) / sizeof(kPatches[0]);

static bool patchVtable() {
    // 1) 定位 libscenejni.so 加载基址（此时它必然已加载：SceneLib.<clinit> 的
    //    System.loadLibrary 在 hook 的 initLibrary 方法体之前执行）
    uintptr_t base = 0;
    if (!findSceneJniBase(&base)) {
        LOGE("libscenejni.so not found in /proc/self/maps");
        return false;
    }
    LOGI("libscenejni.so base=%p", reinterpret_cast<void*>(base));

    // 2) 解析内存 ELF 符号表
    MemElf elf;
    if (!parseLoadedElf(base, &elf)) {
        LOGE("parse in-memory ELF failed");
        return false;
    }
    LOGI("dynsym: %zu symbols", elf.nsyms);

    // 3) 找虚表与全部函数符号
    const Elf64_Sym* vtSym = findMemSymbol(&elf, "_ZTV22AndroidMediaExtensions");
    if (vtSym == nullptr) {
        LOGE("_ZTV22AndroidMediaExtensions not found");
        return false;
    }
    Elf64_Addr funcVal[kPatchCount];
    for (size_t i = 0; i < kPatchCount; i++) {
        const Elf64_Sym* s = findMemSymbol(&elf, kPatches[i].symbol);
        if (s == nullptr) {
            LOGE("symbol missing: %s", kPatches[i].symbol);
            return false;
        }
        funcVal[i] = s->st_value;
    }

    void** vt = reinterpret_cast<void**>(base + vtSym->st_value);
    LOGI("vtable @ %p", reinterpret_cast<void*>(vt));

    // 4) 校验：虚表槽位当前值 == 基址 + 函数符号偏移（防版本布局变化打错槽）
    for (size_t i = 0; i < kPatchCount; i++) {
        void* expected = reinterpret_cast<void*>(base + funcVal[i]);
        void* current = vt[2 + kPatches[i].slot];
        if (current != expected) {
            LOGE("slot %d MISMATCH: vtable=%p expect=%p (%s)",
                 kPatches[i].slot, current, expected, kPatches[i].symbol);
            return false;
        }
    }

    // 5) mprotect 虚表页为可写（虚表在 RELRO 数据段）
    uintptr_t start = reinterpret_cast<uintptr_t>(&vt[2 + 16]);
    uintptr_t end   = reinterpret_cast<uintptr_t>(&vt[2 + 28]) + sizeof(void*);
    uintptr_t pageStart = start & ~static_cast<uintptr_t>(getpagesize() - 1);
    size_t len = end - pageStart;
    if (mprotect(reinterpret_cast<void*>(pageStart), len, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect(%p, %zu) failed: %s",
             reinterpret_cast<void*>(pageStart), len, strerror(errno));
        return false;
    }

    // 6) 替换槽位并验证
    for (const auto& p : kPatches) {
        vt[2 + p.slot] = p.replacement;
    }
    for (const auto& p : kPatches) {
        if (vt[2 + p.slot] != p.replacement) {
            LOGE("VERIFY FAILED slot %d", p.slot);
            return false;
        }
    }

    LOGI("vtable patched OK (%zu slots)", kPatchCount);
    return true;
}

// ==================== JNI 入口 ====================

extern "C" JNIEXPORT jboolean JNICALL
Java_io_wallpaper_soundfix_SoundFixNative_install(JNIEnv* env, jclass, jobject bridge) {
    if (g_bridge != nullptr) return JNI_TRUE; // 已安装
    if (bridge == nullptr) return JNI_FALSE;

    env->GetJavaVM(&g_vm);
    g_bridge = env->NewGlobalRef(bridge);

    jclass cls = env->GetObjectClass(bridge);
    g_create    = env->GetMethodID(cls, "createSound", "([B)J");
    g_play      = env->GetMethodID(cls, "play",       "(J)V");
    g_pause     = env->GetMethodID(cls, "pause",      "(J)V");
    g_stop      = env->GetMethodID(cls, "stop",       "(J)V");
    g_setVol    = env->GetMethodID(cls, "setVolume",  "(JF)V");
    g_getDur    = env->GetMethodID(cls, "getDuration","(J)D");
    g_isPlaying = env->GetMethodID(cls, "isPlaying",  "(J)Z");
    g_isPaused  = env->GetMethodID(cls, "isPaused",   "(J)Z");
    g_isStopped = env->GetMethodID(cls, "isStopped",  "(J)Z");
    g_destroy   = env->GetMethodID(cls, "destroy",    "(J)V");

    if (!g_create || !g_play || !g_pause || !g_stop || !g_setVol ||
        !g_getDur || !g_isPlaying || !g_isPaused || !g_isStopped || !g_destroy) {
        LOGE("SoundBridge methodIDs missing");
        return JNI_FALSE;
    }

    return patchVtable() ? JNI_TRUE : JNI_FALSE;
}

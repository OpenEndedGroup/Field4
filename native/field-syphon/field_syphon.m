// field_syphon.m: see field_syphon.h for the contract. Built by native/field-syphon/build.sh.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <Syphon/Syphon.h>
#include <stdatomic.h>
#include <pthread.h>
#include "field_syphon.h"

#define FS_VERSION 1

static __thread char fsError[1024];

static void setError(NSString *msg) {
    const char *c = msg.UTF8String ?: "unknown error";
    strlcpy(fsError, c, sizeof fsError);
}

int32_t fs_version(void) { return FS_VERSION; }
const char *fs_last_error(void) { return fsError; }
void fs_free_string(char *s) { free(s); }

static char *dupString(NSString *s) { return strdup(s.UTF8String ?: ""); }

static char *jsonOf(id obj) {
    NSError *err = nil;
    NSData *d = [NSJSONSerialization dataWithJSONObject:obj options:0 error:&err];
    if (!d) { setError(err.localizedDescription ?: @"json"); return NULL; }
    return dupString([[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding]);
}

static NSDictionary *summary(NSDictionary *desc) {
    return @{
        @"name": desc[SyphonServerDescriptionNameKey] ?: @"",
        @"appName": desc[SyphonServerDescriptionAppNameKey] ?: @"",
        @"uuid": desc[SyphonServerDescriptionUUIDKey] ?: @"",
    };
}

// --- the Syphon thread -------------------------------------------------------------------------
// One thread with a live CFRunLoop owns discovery (NSDistributedNotificationCenter delivers to the
// run loop that registered) and creates every server and client.

static CFRunLoopRef syphonLoop;
static dispatch_once_t syphonOnce;

static void *syphonThreadMain(void *arg) {
    @autoreleasepool {
        syphonLoop = CFRunLoopGetCurrent();
        CFRetain(syphonLoop);
        // A source that never fires keeps CFRunLoopRun from returning while nothing is registered.
        CFRunLoopSourceContext ctx = {0};
        CFRunLoopSourceRef keepAlive = CFRunLoopSourceCreate(NULL, 0, &ctx);
        CFRunLoopAddSource(syphonLoop, keepAlive, kCFRunLoopDefaultMode);
        dispatch_semaphore_signal((__bridge dispatch_semaphore_t)arg);
    }
    for (;;) {
        @autoreleasepool { CFRunLoopRunInMode(kCFRunLoopDefaultMode, 1.0, false); }
    }
    return NULL;
}

static void onSyphonThread(void (^block)(void)) {
    dispatch_once(&syphonOnce, ^{
        dispatch_semaphore_t ready = dispatch_semaphore_create(0);
        pthread_t t;
        pthread_create(&t, NULL, syphonThreadMain, (__bridge void *)ready);
        pthread_detach(t);
        dispatch_semaphore_wait(ready, DISPATCH_TIME_FOREVER);
    });
    if (CFRunLoopGetCurrent() == syphonLoop) { block(); return; }
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    CFRunLoopPerformBlock(syphonLoop, kCFRunLoopDefaultMode, ^{
        @autoreleasepool { block(); }
        dispatch_semaphore_signal(done);
    });
    CFRunLoopWakeUp(syphonLoop);
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
}

int32_t fs_pump(int32_t ms) {
    if (!pthread_main_np()) return 0;
    @autoreleasepool { CFRunLoopRunInMode(kCFRunLoopDefaultMode, MAX(ms, 0) / 1000.0, false); }
    return 1;
}

static id<MTLDevice> sharedDevice(void) {
    static id<MTLDevice> device;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ device = MTLCreateSystemDefaultDevice(); });
    return device;
}

static id<MTLCommandQueue> sharedQueue(void) {
    static id<MTLCommandQueue> queue;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ queue = [sharedDevice() newCommandQueue]; });
    return queue;
}

// Touches the directory on the Syphon thread so it starts listening, then lets announcements
// arrive for up to waitMs.
static NSArray *serversWaiting(int32_t waitMs, BOOL (^until)(NSArray *)) {
    __block NSArray *servers = nil;
    uint64_t deadline = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) + (uint64_t)MAX(waitMs, 0) * 1000000ull;
    for (;;) {
        onSyphonThread(^{ servers = [[SyphonServerDirectory sharedDirectory].servers copy]; });
        if ((until && until(servers)) || clock_gettime_nsec_np(CLOCK_UPTIME_RAW) >= deadline) break;
        if (pthread_main_np()) fs_pump(20); else usleep(20000);
    }
    return servers ?: @[];
}

// --- servers -----------------------------------------------------------------------------------

struct fs_server {
    void *server;      // SyphonMetalServer, retained
    void *staging;     // id<MTLTexture>, RGBA8, retained; reallocated when the size changes
};

fs_server *fs_server_new(const char *name) {
    @autoreleasepool {
        id<MTLDevice> device = sharedDevice();
        if (!device) { setError(@"no Metal device"); return NULL; }
        NSString *n = name ? [NSString stringWithUTF8String:name] : nil;
        __block SyphonMetalServer *server = nil;
        onSyphonThread(^{ server = [[SyphonMetalServer alloc] initWithName:n device:device options:nil]; });
        if (!server) { setError(@"SyphonMetalServer init failed"); return NULL; }
        fs_server *s = calloc(1, sizeof *s);
        s->server = (__bridge_retained void *)server;
        return s;
    }
}

static int32_t publishTexture(fs_server *s, id<MTLTexture> tex, BOOL flipped) {
    SyphonMetalServer *server = (__bridge SyphonMetalServer *)s->server;
    id<MTLCommandBuffer> cb = [sharedQueue() commandBuffer];
    [server publishFrameTexture:tex onCommandBuffer:cb imageRegion:NSMakeRect(0, 0, tex.width, tex.height) flipped:flipped];
    [cb commit];
    [cb waitUntilCompleted];
    if (cb.error) { setError(cb.error.localizedDescription); return -1; }
    return 0;
}

static int32_t publishPixels(fs_server *s, const uint8_t *px, int32_t width, int32_t height, int32_t stride, MTLPixelFormat format) {
    @autoreleasepool {
        if (!s || !px || width <= 0 || height <= 0) { setError(@"bad arguments"); return -1; }
        if (stride == 0) stride = width * 4;
        id<MTLTexture> tex = (__bridge id<MTLTexture>)s->staging;
        if (!tex || (int32_t)tex.width != width || (int32_t)tex.height != height || tex.pixelFormat != format) {
            if (s->staging) CFRelease(s->staging);
            MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
                                                                                         width:width height:height mipmapped:NO];
            d.usage = MTLTextureUsageShaderRead;
            d.storageMode = MTLStorageModeShared;
            tex = [sharedDevice() newTextureWithDescriptor:d];
            if (!tex) { s->staging = NULL; setError(@"could not allocate a staging texture"); return -1; }
            s->staging = (__bridge_retained void *)tex;
        }
        [tex replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:px bytesPerRow:stride];
        // Metal's texture origin is the top-left, so top-row-first pixels are not flipped.
        return publishTexture(s, tex, NO);
    }
}

int32_t fs_server_publish_rgba(fs_server *s, const uint8_t *rgba, int32_t width, int32_t height, int32_t stride) {
    return publishPixels(s, rgba, width, height, stride, MTLPixelFormatRGBA8Unorm);
}

int32_t fs_server_publish_bgra(fs_server *s, const uint8_t *bgra, int32_t width, int32_t height, int32_t stride) {
    return publishPixels(s, bgra, width, height, stride, MTLPixelFormatBGRA8Unorm);
}

int32_t fs_server_publish_iosurface(fs_server *s, void *surfaceRef, int32_t flipped) {
    @autoreleasepool {
        if (!s || !surfaceRef) { setError(@"bad arguments"); return -1; }
        IOSurfaceRef surface = (IOSurfaceRef)surfaceRef;
        size_t w = IOSurfaceGetWidth(surface), h = IOSurfaceGetHeight(surface);
        MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                     width:w height:h mipmapped:NO];
        d.usage = MTLTextureUsageShaderRead;
        id<MTLTexture> tex = [sharedDevice() newTextureWithDescriptor:d iosurface:surface plane:0];
        if (!tex) { setError(@"could not wrap the IOSurface as a BGRA8 texture"); return -1; }
        return publishTexture(s, tex, flipped != 0);
    }
}

int32_t fs_server_has_clients(fs_server *s) {
    if (!s) { setError(@"null server"); return -1; }
    return ((__bridge SyphonMetalServer *)s->server).hasClients ? 1 : 0;
}

char *fs_server_description(fs_server *s) {
    @autoreleasepool {
        if (!s) { setError(@"null server"); return NULL; }
        return jsonOf(summary(((__bridge SyphonMetalServer *)s->server).serverDescription));
    }
}

void fs_server_free(fs_server *s) {
    if (!s) return;
    @autoreleasepool {
        void *server = s->server;
        onSyphonThread(^{
            SyphonMetalServer *srv = (__bridge_transfer SyphonMetalServer *)server;
            [srv stop];
        });
        if (s->staging) CFRelease(s->staging);
        free(s);
    }
}

// --- IOSurfaces ---------------------------------------------------------------------------------

void *fs_iosurface_create(int32_t width, int32_t height) {
    @autoreleasepool {
        if (width <= 0 || height <= 0) { setError(@"bad size"); return NULL; }
        size_t bpr = IOSurfaceAlignProperty(kIOSurfaceBytesPerRow, (size_t)width * 4);
        size_t page = 16384;
        size_t alloc = ((bpr * height) + page - 1) / page * page;
        NSDictionary *props = @{
            (id)kIOSurfaceWidth: @(width),
            (id)kIOSurfaceHeight: @(height),
            (id)kIOSurfaceBytesPerElement: @4,
            (id)kIOSurfaceBytesPerRow: @(bpr),
            (id)kIOSurfaceAllocSize: @(alloc),
            (id)kIOSurfacePixelFormat: @((uint32_t)'BGRA'),
        };
        IOSurfaceRef surface = IOSurfaceCreate((__bridge CFDictionaryRef)props);
        if (!surface) { setError(@"IOSurfaceCreate failed"); return NULL; }
        return (void *)surface;
    }
}

// --- discovery ---------------------------------------------------------------------------------

char *fs_list_servers(int32_t waitMs) {
    @autoreleasepool {
        NSArray *servers = serversWaiting(waitMs, ^BOOL(NSArray *a) { return a.count > 0; });
        NSMutableArray *out = [NSMutableArray array];
        for (NSDictionary *d in servers) [out addObject:summary(d)];
        return jsonOf(out);
    }
}

// --- clients -----------------------------------------------------------------------------------

struct fs_client {
    void *client;            // SyphonMetalClient, retained
    _Atomic int64_t frames;
    pthread_mutex_t lock;
    pthread_cond_t cond;
};

static NSDictionary *matchServer(NSArray *servers, NSString *m) {
    if (m.length == 0) return servers.firstObject;
    for (NSString *key in @[SyphonServerDescriptionUUIDKey, SyphonServerDescriptionNameKey, SyphonServerDescriptionAppNameKey])
        for (NSDictionary *d in servers)
            if ([d[key] isEqual:m]) return d;
    return nil;
}

fs_client *fs_client_new(const char *match, int32_t waitMs) {
    @autoreleasepool {
        id<MTLDevice> device = sharedDevice();
        if (!device) { setError(@"no Metal device"); return NULL; }
        NSString *m = match ? [NSString stringWithUTF8String:match] : @"";
        NSArray *servers = serversWaiting(waitMs, ^BOOL(NSArray *a) { return matchServer(a, m) != nil; });
        NSDictionary *desc = matchServer(servers, m);
        if (!desc) { setError([NSString stringWithFormat:@"no Syphon server matches \"%@\" (%lu announced)", m, (unsigned long)servers.count]); return NULL; }
        fs_client *c = calloc(1, sizeof *c);
        pthread_mutex_init(&c->lock, NULL);
        pthread_cond_init(&c->cond, NULL);
        __block SyphonMetalClient *client = nil;
        onSyphonThread(^{
            client = [[SyphonMetalClient alloc] initWithServerDescription:desc device:device options:nil
                                                          newFrameHandler:^(SyphonMetalClient *cl) {
                pthread_mutex_lock(&c->lock);
                atomic_fetch_add(&c->frames, 1);
                pthread_cond_broadcast(&c->cond);
                pthread_mutex_unlock(&c->lock);
            }];
        });
        if (!client || !client.isValid) {
            setError(@"SyphonMetalClient could not connect");
            pthread_mutex_destroy(&c->lock); pthread_cond_destroy(&c->cond); free(c);
            return NULL;
        }
        c->client = (__bridge_retained void *)client;
        return c;
    }
}

int64_t fs_client_frame_count(fs_client *c) {
    if (!c) { setError(@"null client"); return -1; }
    return atomic_load(&c->frames);
}

int64_t fs_client_wait_frame(fs_client *c, int64_t after, int32_t waitMs) {
    if (!c) { setError(@"null client"); return -1; }
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    int64_t ns = ts.tv_nsec + (int64_t)MAX(waitMs, 0) * 1000000ll;
    ts.tv_sec += ns / 1000000000ll; ts.tv_nsec = ns % 1000000000ll;
    pthread_mutex_lock(&c->lock);
    while (atomic_load(&c->frames) <= after)
        if (pthread_cond_timedwait(&c->cond, &c->lock, &ts) != 0) break;
    int64_t now = atomic_load(&c->frames);
    pthread_mutex_unlock(&c->lock);
    return now;
}

int32_t fs_client_copy_rgba(fs_client *c, uint8_t *rgba, int64_t capacity, int32_t *width, int32_t *height) {
    @autoreleasepool {
        if (!c) { setError(@"null client"); return -1; }
        id<MTLTexture> frame = [(__bridge SyphonMetalClient *)c->client newFrameImage];
        if (!frame) { if (width) *width = 0; if (height) *height = 0; return 0; }
        int32_t w = (int32_t)frame.width, h = (int32_t)frame.height;
        if (width) *width = w;
        if (height) *height = h;
        int64_t need = (int64_t)w * h * 4;
        if (!rgba || capacity < need) { setError([NSString stringWithFormat:@"buffer holds %lld bytes, the frame needs %lld", capacity, need]); return -2; }
        // Syphon frames are private-storage BGRA8; blit into a shared buffer, then swizzle.
        id<MTLBuffer> buf = [sharedDevice() newBufferWithLength:need options:MTLResourceStorageModeShared];
        id<MTLCommandBuffer> cb = [sharedQueue() commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromTexture:frame sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(w, h, 1) toBuffer:buf destinationOffset:0
          destinationBytesPerRow:w * 4 destinationBytesPerImage:need];
        [blit endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.error) { setError(cb.error.localizedDescription); return -1; }
        const uint8_t *src = buf.contents;
        BOOL bgra = frame.pixelFormat == MTLPixelFormatBGRA8Unorm || frame.pixelFormat == MTLPixelFormatBGRA8Unorm_sRGB;
        for (int64_t i = 0; i < need; i += 4) {
            rgba[i] = bgra ? src[i + 2] : src[i];
            rgba[i + 1] = src[i + 1];
            rgba[i + 2] = bgra ? src[i] : src[i + 2];
            rgba[i + 3] = src[i + 3];
        }
        return 1;
    }
}

char *fs_client_description(fs_client *c) {
    @autoreleasepool {
        if (!c) { setError(@"null client"); return NULL; }
        return jsonOf(summary(((__bridge SyphonMetalClient *)c->client).serverDescription));
    }
}

void fs_client_free(fs_client *c) {
    if (!c) return;
    @autoreleasepool {
        void *client = c->client;
        onSyphonThread(^{
            SyphonMetalClient *cl = (__bridge_transfer SyphonMetalClient *)client;
            [cl stop];
        });
        pthread_mutex_destroy(&c->lock);
        pthread_cond_destroy(&c->cond);
        free(c);
    }
}

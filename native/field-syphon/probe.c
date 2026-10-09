// field_syphon_probe: a separate process on the other end of a Syphon link, for tests.
//
//   field_syphon_probe list [waitMs]                 JSON array of announced servers
//   field_syphon_probe serve <name> <seconds> [w h [w2 h2]]
//                                                    publishes the test pattern below at ~60 Hz
//                                                    (at w2 x h2 for the second half, if given)
//   field_syphon_probe receive <match> [waitMs]      prints {"width","height","frames","pixels":[...]}
//                                                    with the RGBA of the pattern's probe points
//
// The test pattern: four quadrants, top-left red (255,0,0), top-right green (0,255,0), bottom-left
// blue (0,0,255), bottom-right (255,255,255) with alpha 255; and pixel (1,1) is (17,34,51).
// Probe points (x,y, top row first): (1,1), (w/4,h/4), (3w/4,h/4), (w/4,3h/4), (3w/4,3h/4).
#include "field_syphon.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void pattern(uint8_t *p, int w, int h, int frame) {
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            uint8_t *q = p + ((size_t)y * w + x) * 4;
            int right = x >= w / 2, bottom = y >= h / 2;
            q[0] = (!right && !bottom) || (right && bottom) ? 255 : 0;
            q[1] = (right && !bottom) || (right && bottom) ? 255 : 0;
            q[2] = (!right && bottom) || (right && bottom) ? 255 : 0;
            q[3] = 255;
        }
    uint8_t *m = p + ((size_t)1 * w + 1) * 4;
    m[0] = 17; m[1] = 34; m[2] = 51; m[3] = 255;
    (void)frame;
}

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: field_syphon_probe list|serve|receive ...\n"); return 2; }
    if (!strcmp(argv[1], "list")) {
        char *s = fs_list_servers(argc > 2 ? atoi(argv[2]) : 1000);
        if (!s) { fprintf(stderr, "%s\n", fs_last_error()); return 1; }
        printf("%s\n", s); fs_free_string(s); return 0;
    }
    if (!strcmp(argv[1], "serve") && argc >= 4) {
        int w = argc > 5 ? atoi(argv[4]) : 64, h = argc > 5 ? atoi(argv[5]) : 48;
        fs_server *srv = fs_server_new(argv[2]);
        if (!srv) { fprintf(stderr, "%s\n", fs_last_error()); return 1; }
        int w2 = argc > 7 ? atoi(argv[6]) : w, h2 = argc > 7 ? atoi(argv[7]) : h;
        uint8_t *p = malloc((size_t)(w > w2 ? w : w2) * (h > h2 ? h : h2) * 4);
        double seconds = atof(argv[3]);
        char *d = fs_server_description(srv);
        printf("%s\n", d); fflush(stdout); fs_free_string(d);
        int total = (int)(seconds * 60);
        for (int f = 0; f < total; f++) {
            if (f == total / 2) { w = w2; h = h2; }
            pattern(p, w, h, f);
            if (fs_server_publish_rgba(srv, p, w, h, 0) < 0) { fprintf(stderr, "%s\n", fs_last_error()); return 1; }
            fs_pump(16);
        }
        fs_server_free(srv); free(p); return 0;
    }
    if (!strcmp(argv[1], "receive") && argc >= 3) {
        int waitMs = argc > 3 ? atoi(argv[3]) : 5000;
        fs_client *c = fs_client_new(argv[2], waitMs);
        if (!c) { fprintf(stderr, "%s\n", fs_last_error()); return 1; }
        int64_t frames = fs_client_wait_frame(c, 0, waitMs);
        int w = 0, h = 0;
        fs_client_copy_rgba(c, NULL, 0, &w, &h);
        if (w <= 0 || h <= 0) { fprintf(stderr, "no frame after %d ms\n", waitMs); return 1; }
        uint8_t *p = malloc((size_t)w * h * 4);
        if (fs_client_copy_rgba(c, p, (int64_t)w * h * 4, &w, &h) != 1) { fprintf(stderr, "%s\n", fs_last_error()); return 1; }
        int pts[5][2] = {{1, 1}, {w / 4, h / 4}, {3 * w / 4, h / 4}, {w / 4, 3 * h / 4}, {3 * w / 4, 3 * h / 4}};
        printf("{\"width\":%d,\"height\":%d,\"frames\":%lld,\"pixels\":[", w, h, (long long)frames);
        for (int i = 0; i < 5; i++) {
            uint8_t *q = p + ((size_t)pts[i][1] * w + pts[i][0]) * 4;
            printf("%s[%d,%d,%d,%d]", i ? "," : "", q[0], q[1], q[2], q[3]);
        }
        printf("]}\n");
        fs_client_free(c); free(p); return 0;
    }
    fprintf(stderr, "usage: field_syphon_probe list|serve|receive ...\n");
    return 2;
}

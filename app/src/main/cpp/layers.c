#include <stdlib.h>
#include <string.h>

#include <mgba/core/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>
#include <mgba/internal/gba/video.h>
#include <mgba/internal/gba/renderers/video-software.h>

#include "layers.h"

enum { PASS_OBJ, PASS_BG0, PASS_BG1, PASS_BG2, PASS_BG3, PASSES };

/* The backdrop colour the one-layer copies draw instead of the game's, so "nothing of this layer here" is plain to see. */
#define SENTINEL_555 0x7C1F

struct Probe {
    struct GBA* gba;
    struct GBAVideoSoftwareRenderer* real;
    struct GBAVideoRenderer orig;       /* the real renderer's own functions, called first by every wrapper */
    struct GBAVideoSoftwareRenderer pass[PASSES];
    color_t out[PASSES][LAYERS_W * LAYERS_H];
    uint8_t depth[LAYERS_W * LAYERS_H];
    uint32_t frames;
    int floorBg, lastH, lastV;          /* the background that shows most, and its scroll last frame */
    int32_t worldX, worldY;             /* that scroll followed across the 512 px wrap */
};

static struct Probe* g_probe;

uint8_t layers_depth_of(bool sprite, int priority, int bg) {
    /* backgrounds close together (a game's floor is often split over two of them), sprites well in front */
    if (sprite) return (uint8_t) (225 + (3 - priority) * 10);
    return (uint8_t) (100 + (3 - priority) * 20 - bg);
}

/* What a copy is fed of a register write: the same, except that it never blends (its pixels stay its own colours). */
static uint16_t pass_value(uint32_t address, uint16_t value) {
    return address == REG_BLDCNT ? 0 : value;
}

static uint16_t wrap_write_register(struct GBAVideoRenderer* r, uint32_t address, uint16_t value) {
    struct Probe* p = g_probe;
    uint16_t result = p->orig.writeVideoRegister(r, address, value);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.writeVideoRegister(&p->pass[i].d, address, pass_value(address, value));
    return result;
}

static void wrap_write_vram(struct GBAVideoRenderer* r, uint32_t address) {
    struct Probe* p = g_probe;
    p->orig.writeVRAM(r, address);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.writeVRAM(&p->pass[i].d, address);
}

static void wrap_write_palette(struct GBAVideoRenderer* r, uint32_t address, uint16_t value) {
    struct Probe* p = g_probe;
    p->orig.writePalette(r, address, value);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.writePalette(&p->pass[i].d, address, address == 0 ? SENTINEL_555 : value);
}

static void wrap_write_oam(struct GBAVideoRenderer* r, uint32_t oam) {
    struct Probe* p = g_probe;
    p->orig.writeOAM(r, oam);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.writeOAM(&p->pass[i].d, oam);
}

static void wrap_draw_scanline(struct GBAVideoRenderer* r, int y) {
    struct Probe* p = g_probe;
    p->orig.drawScanline(r, y);
    /* a layer switched off draws nothing anyway (and keeps no per-line state), so its copy can rest */
    if (GBARegisterDISPCNTIsObjEnable(p->real->dispcnt)) p->pass[PASS_OBJ].d.drawScanline(&p->pass[PASS_OBJ].d, y);
    for (int b = 0; b < 4; b++) {
        if (p->real->bg[b].enabled) p->pass[PASS_BG0 + b].d.drawScanline(&p->pass[PASS_BG0 + b].d, y);
    }
}

/* Names the layer each pixel of the real frame came from. A copy's pixel that is not the sentinel is its layer's; the
 * copy's flags keep that pixel's priority (nothing else was composed over it). Of the layers present at a pixel, the
 * frontmost whose colour is the frame's wins (blending and fades change colours: then simply the frontmost). */
static void resolve(struct Probe* p) {
    color_t* real = p->real->outputBuffer;
    int stride = p->real->outputBufferStride;
    color_t sentinel[PASSES];
    for (int i = 0; i < PASSES; i++) sentinel[i] = p->pass[i].normalPalette[0] & 0x00FFFFFF;
    bool active[PASSES];
    active[PASS_OBJ] = GBARegisterDISPCNTIsObjEnable(p->real->dispcnt);
    for (int b = 0; b < 4; b++) active[PASS_BG0 + b] = p->real->bg[b].enabled != 0;
    int shown[PASSES] = { 0 };
    for (int y = 0; y < LAYERS_H; y++) {
        color_t* line = real + (size_t) y * (size_t) stride;
        for (int x = 0; x < LAYERS_W; x++) {
            size_t i = (size_t) y * LAYERS_W + (size_t) x;
            color_t want = line[x] & 0x00FFFFFF;
            int best = 0, bestMatch = 0, bestPass = -1;
            for (int k = 0; k < PASSES; k++) {
                if (!active[k]) continue;
                color_t px = p->out[k][i];
                if ((px & 0x00FFFFFF) == sentinel[k]) continue;
                int d = layers_depth_of(k == PASS_OBJ, (int) (px >> 30), k - PASS_BG0);
                int match = (px & 0x00FFFFFF) == want;
                if (match > bestMatch || (match == bestMatch && d > best)) {
                    best = d;
                    bestMatch = match;
                    bestPass = k;
                }
            }
            if (bestPass >= 0) shown[bestPass]++;
            p->depth[i] = (uint8_t) best;
            line[x] = want | (color_t) best << 24; /* the frame carries its depth in alpha, to the GL side and its shaders */
        }
    }
    /* Where the world is: the scroll of the background that shows most (a game's floor), so effects drawn over the
     * picture (clouds) can stay put on the ground while the camera moves. */
    int floor = PASS_BG0;
    for (int k = PASS_BG0; k < PASSES; k++) {
        if (shown[k] > shown[floor]) floor = k;
    }
    int bg = floor - PASS_BG0;
    int h = p->gba->memory.io[(REG_BG0HOFS >> 1) + bg * 2] & 0x1FF;
    int v = p->gba->memory.io[(REG_BG0VOFS >> 1) + bg * 2] & 0x1FF;
    if (bg == p->floorBg) {
        p->worldX += ((h - p->lastH + 256 + 512) % 512) - 256;
        p->worldY += ((v - p->lastV + 256 + 512) % 512) - 256;
    }
    p->floorBg = bg;
    p->lastH = h;
    p->lastV = v;
}

static void wrap_finish_frame(struct GBAVideoRenderer* r) {
    struct Probe* p = g_probe;
    p->orig.finishFrame(r);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.finishFrame(&p->pass[i].d);
    if (p->real->outputBuffer) resolve(p);
    p->frames++;
}

static void wrap_reset(struct GBAVideoRenderer* r) {
    struct Probe* p = g_probe;
    p->orig.reset(r);
    for (int i = 0; i < PASSES; i++) p->pass[i].d.reset(&p->pass[i].d);
}

/* A copy brought up to the machine's present state, the way mGBA attaches a renderer. */
static void start_pass(struct Probe* p, int k) {
    struct GBAVideoSoftwareRenderer* s = &p->pass[k];
    struct GBAVideo* video = &p->gba->video;
    GBAVideoSoftwareRendererCreate(s);
    s->outputBuffer = p->out[k];
    s->outputBufferStride = LAYERS_W;
    s->d.cache = NULL;
    s->d.palette = video->palette;
    s->d.vram = video->vram;
    s->d.oam = &video->oam;
    for (int b = 0; b < 4; b++) s->d.disableBG[b] = k != PASS_BG0 + b;
    s->d.disableOBJ = k != PASS_OBJ;
    s->d.init(&s->d);
    s->d.reset(&s->d);
    uint16_t* io = p->gba->memory.io;
    s->d.writeVideoRegister(&s->d, REG_DISPCNT, io[REG_DISPCNT >> 1]);
    s->d.writeVideoRegister(&s->d, REG_GREENSWP, io[REG_GREENSWP >> 1]);
    for (uint32_t address = REG_BG0CNT; address < 0x56; address += 2) {
        if (address == 0x4E) continue;
        s->d.writeVideoRegister(&s->d, address, pass_value(address, io[address >> 1]));
    }
    for (uint32_t i = 0; i < 512; i++) s->d.writePalette(&s->d, i * 2, i == 0 ? SENTINEL_555 : video->palette[i]);
    s->d.writeOAM(&s->d, 0);
    s->d.writeVRAM(&s->d, 0);
}

void gbaduo_layers_set(struct mCore* core, bool on) {
    if (!on) {
        struct Probe* p = g_probe;
        if (!p) return;
        struct GBAVideoRenderer* r = &p->real->d;
        r->writeVideoRegister = p->orig.writeVideoRegister;
        r->writeVRAM = p->orig.writeVRAM;
        r->writePalette = p->orig.writePalette;
        r->writeOAM = p->orig.writeOAM;
        r->drawScanline = p->orig.drawScanline;
        r->finishFrame = p->orig.finishFrame;
        r->reset = p->orig.reset;
        for (int i = 0; i < PASSES; i++) p->pass[i].d.deinit(&p->pass[i].d);
        g_probe = NULL;
        free(p);
        return;
    }
    if (g_probe || !core || core->platform(core) != mPLATFORM_GBA) return;
    struct GBA* gba = core->board;
    struct GBAVideoRenderer* r = gba->video.renderer;
    /* only mGBA's software renderer (the app's) can be followed like this */
    if (!r || r->drawScanline == NULL || r->getPixels == NULL) return;
    struct Probe* p = calloc(1, sizeof(*p));
    if (!p) return;
    p->gba = gba;
    p->real = (struct GBAVideoSoftwareRenderer*) r;
    p->orig = *r;
    for (int k = 0; k < PASSES; k++) start_pass(p, k);
    g_probe = p;
    r->writeVideoRegister = wrap_write_register;
    r->writeVRAM = wrap_write_vram;
    r->writePalette = wrap_write_palette;
    r->writeOAM = wrap_write_oam;
    r->drawScanline = wrap_draw_scanline;
    r->finishFrame = wrap_finish_frame;
    r->reset = wrap_reset;
}

bool gbaduo_layers_on(void) {
    return g_probe != NULL;
}

const uint8_t* gbaduo_layers_depth(void) {
    return g_probe && g_probe->frames ? g_probe->depth : NULL;
}

void gbaduo_layers_world(int32_t* x, int32_t* y) {
    *x = g_probe ? g_probe->worldX : 0;
    *y = g_probe ? g_probe->worldY : 0;
}

uint32_t gbaduo_layers_frames(void) {
    return g_probe ? g_probe->frames : 0;
}

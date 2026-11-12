// Lift tracker gym pod v4: slim pebble around the custom gym pod v1 board.
// Same band system as v3 (one continuous 20 mm loop band through a channel
// in the underside and a keeper at each end), but the XIAO stack is gone:
//   band channel | floor | battery + motor bay | board | parts | lid
//
// Board: gym pod v1.1, 38 x 27 mm, 1.6 mm, parts on top (tallest is the
// USB-C at ~3.2 mm); motor wire pads on the bottom at the antenna end.
// Battery: Jauch LP402025JU (4.5 x 20.5 x 27 mm, 150 mAh) under the USB
// half of the board, plugged into the JST-SH socket J102; the motor sits
// beside it at the antenna end.
// The antenna end (-X) has no metal near it: plastic only.
//
// Two printed parts:
//   base : band channel + keepers, floor, battery/motor bay, ledge the board sits on
//   lid  : domed cap, presses the board onto the ledge; button flexure + LED windows
//
// Axes when worn: the band runs along X; USB-C at the +X end.
// Measure the real band and change band_t / strap_w before printing.
//
// PRINT: black filament (matte black PETG preferred: handles sweat and a
// hot car better than PLA). 0.2 mm layers, 3 walls, 20% infill, no supports.
// The LED windows are 0.4 mm skins, so in black they only show the LEDs
// faintly; if they don't glow through enough, drill them to 1.5 mm.

part = "all";   // "base", "lid", "all", "assembled"

// ---------- Board (gym pod v1) ----------
pcb_l = 38.0;  pcb_w = 27.0;  pcb_r = 4.0;  pcb_t = 1.6;
parts_h = 3.3;          // tallest top-side part (USB-C shell) + margin
usb_y   = 1.5;          // USB-C centre (board y = 12 -> pod y = 27/2 - 12)
usb_zc  = 1.6;          // USB-C opening centre above the board top
// from the board files (mm from the board's top-left corner)
btn  = [31.6, 3.4];     // SW101 user button
leds = [[29.4, 22.2], [32.4, 24.2]];   // D103 red charge, D102 green user

// ---------- Under-board bay ----------
bay_h  = 5.0;           // Jauch LP402025JU is 4.5 mm; tape + swell allowance

// ---------- Band ----------
strap_w  = 20.0;        // band width (measure)
band_t   = 1.8;         // band thickness (measure!)
band_clr = 0.2;
end_len  = 4.5;
keeper_l = 2.5;
keeper_t = 0.8;
keeper_pinch = -0.2;
ridge_h  = 0.3;  ridge_n = 3;

// ---------- Shape / print ----------
plan_r = 6.0;  top_r = 2.5;  bot_r = 1.0;
clr = 0.3;  fit_clr = 0.12;  wall = 1.6;  floor_t = 0.8;  lid_t = 1.0;  lap_h = 1.2;
ledge_w = 1.0;          // board rests on this all round (board keeps 1 mm edge free of parts? check)
logo_h = 8.0;  logo_depth = 0.4;
$fn = 40;

// ---------- Derived ----------
cav_l = pcb_l + 2*clr;  cav_w = pcb_w + 2*clr;
body_l = cav_l + 2*wall;
L = body_l + 2*end_len;
W = cav_w + 2*wall;
ch_w = strap_w + 2*band_clr;
ch_h = band_t + 0.3;
z_floor = ch_h;
z_bay   = z_floor + floor_t;
z_pcb   = z_bay + bay_h;              // board underside
z_top   = z_pcb + pcb_t;              // board top
z_split = z_top;                      // lid lands on the board edge
H       = z_top + parts_h + lid_t;

function pod(p) = [p[0] - pcb_l/2, pcb_w/2 - p[1]];   // board mm -> pod XY

module rr(l, w, r) {
    r2 = max(min(r, min(l, w)/2 - 0.01), 0.01);
    offset(r = r2) square([l - 2*r2, w - 2*r2], center = true);
}
function inset(z) =
    z < bot_r     ? bot_r - z :
    z > H - top_r ? top_r - sqrt(max(top_r*top_r - (z - (H - top_r))*(z - (H - top_r)), 0)) : 0;
module pebble() {
    zs = concat([0, 0.15, 0.4, 0.7, 1.0], [for (i = [0:12]) H - top_r + i*top_r/12]);
    hull() for (z = zs) translate([0, 0, z]) linear_extrude(0.001)
        rr(L - 2*inset(z), W - 2*inset(z), plan_r - inset(z));
}
module cav(z0, z1, grow = 0) {
    translate([0, 0, z0]) linear_extrude(z1 - z0) rr(cav_l + 2*grow, cav_w + 2*grow, pcb_r + clr + grow);
}
module r_logo(h = 9) {
    k = h / 84;
    scale([k, -k]) translate([-548, -110]) {
        translate([510, 68]) square([15, 84]);
        translate([533, 68]) square([17, 12]);
        translate([533, 104]) square([17, 12]);
        translate([550, 92]) difference() {
            circle(r = 24, $fn = 96); circle(r = 12, $fn = 96);
            translate([-30, -30]) square([30, 60]);
        }
        polygon([[543, 118], [559, 118], [586, 152], [570, 152]]);
        polygon([[533, 134], [547.7, 134], [555.6, 144], [533, 144]]);
    }
}
module band_cut() {
    k0 = body_l/2 + 0.3;  k1 = k0 + keeper_l;
    translate([-k0, -ch_w/2, -1]) cube([2*k0, ch_w, ch_h + 1]);
    for (s = [-1, 1]) {
        translate([s > 0 ? k0 - 0.01 : -k1 - 0.01, -ch_w/2, keeper_t])
            cube([keeper_l + 0.02, ch_w, band_t - keeper_pinch]);
        translate([s > 0 ? k1 : -L/2 - 1, -ch_w/2, -1])
            cube([L/2 - k1 + 1, ch_w, keeper_t + ch_h + 1]);
    }
}
module usb_cut() {   // cable plug overmold through the +X end
    translate([pcb_l/2 - 1, usb_y, z_top + usb_zc]) rotate([0, 90, 0]) hull()
        for (dy = [-2.75, 2.75]) translate([0, dy, 0]) cylinder(d = 6.6, h = L);
}
module base() {
    difference() {
        union() {
            intersection() { pebble(); translate([-L, -W, 0]) cube([2*L, 2*W, z_split]); }
            for (s = [-1, 1], i = [0 : ridge_n - 1]) {
                x = body_l/2 + 0.3 + (i + 0.5) * keeper_l / ridge_n;
                translate([s*x - 0.25, -ch_w/2, keeper_t - 0.01]) cube([0.5, ch_w, ridge_h + 0.01]);
            }
            // half-lap tongue: inner half of the wall continues up
            difference() { cav(z_split - 0.01, z_split + lap_h, wall/2); cav(z_split - 1, z_split + lap_h + 1); }
        }
        band_cut();
        cav(z_pcb, z_split + lap_h + 1);                      // board pocket
        cav(z_bay, z_pcb + 0.01, -ledge_w);                   // battery + motor bay (leaves the ledge)
        usb_cut();
    }
}
module lid() {
    difference() {
        intersection() { pebble(); translate([-L, -W, z_split]) cube([2*L, 2*W, H]); }
        cav(z_split - 1, H - lid_t);                          // parts space
        cav(z_split - 1, z_split + lap_h + fit_clr, wall/2 + fit_clr);
        usb_cut();
        // button: U-shaped slot makes a flexure tab; a post under it presses SW101
        b = pod(btn);
        translate([b[0], b[1], H - lid_t - 1]) linear_extrude(lid_t + 2) difference() {
            square([7, 7], center = true); square([5.6, 5.6], center = true);
            translate([-5, -0.7]) square([1.6, 1.4]);         // hinge toward -X
        }
        // LED windows: thin (0.4 mm) skin over each LED, glows through
        for (p = leds) { q = pod(p); translate([q[0], q[1], z_split]) cylinder(d = 2.2, h = H - lid_t - z_split + 0.6); }
        translate([-6, 0, H - logo_depth]) linear_extrude(1) r_logo(logo_h);
    }
    // plunger under the button tab
    b = pod(btn);
    translate([b[0], b[1], z_top + 1.6]) cylinder(d = 2.4, h = H - lid_t - z_top - 1.6 + 0.01);
}
if (part == "base") base();
if (part == "lid")  translate([0, 0, -z_split]) lid();
if (part == "all") { color("#111111") base(); color("#111111") translate([0, W + 8, -z_split]) lid(); }
if (part == "assembled") {
    color("#111111") base();
    color("#2d6a3e") translate([0, 0, z_pcb]) linear_extrude(pcb_t) rr(pcb_l, pcb_w, pcb_r);
    color("#1a1a1a", 0.85) lid();
}
echo(str("v4 size (mm): ", L, " long x ", W, " wide x ", H, " thick"));

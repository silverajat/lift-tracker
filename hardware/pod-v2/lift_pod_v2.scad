// Lift pod v2 case: the Fitbit-sized one, around the v2 board (33 x 17 mm,
// 0.8 mm thick). Made to be printed at JLC3DP:
//   - SLA black resin, sanded + "mineral oil" finish (smooth, dry touch), or
//   - MJF PA12-HP nylon, dyed black (tougher, slightly grainy).
//
//   lid   : thin cap over the parts, two LED holes, engraved logo
//   board : rests on a ledge all round
//   bay   : 301120 cell (datasheet max 3.0 x 11.0 x 20.5 mm) + 8 mm coin motor
//   floor : 2 holes for the dock's spring pins, 2 magnet pockets
//   lugs  : a band slot at each end (band loops through, like a watch),
//           so the floor stays free for the dock and the pod stays thin
//
// Every size below comes from a datasheet maximum + clearance, not from a
// measured sample: the cell can't rattle (pocket = max + 0.3 mm, plus a
// 0.3 mm foam pad), and parts from any batch fit.
//
// Axes: band along X; antenna at -X (plastic only around it).
// Board coordinates (KiCad, mm from the board's top-left) map to the pod
// with pod(). Positions come from podv2/placement.py.

part = "all";   // "base", "lid", "all", "assembled"

// ---------- Board ----------
pcb_l = 33.0;  pcb_w = 17.0;  pcb_r = 3.0;  pcb_t = 0.8;
parts_h = 2.45;         // tallest part: MDBT50Q module 2.2 mm (+ margin)

// ---------- Under the board (datasheet maximums) ----------
cell = [20.5, 11.0, 3.0];       // 301120 LiPo, L x W x T max (SparkFun/DTP datasheet)
cell_at = [12.5 + cell[0]/2, 0.6 + cell[1]/2];   // board coords of the cell centre
motor_d = 8.0;  motor_t = 2.5;  motor_at = [8.5, 5.0];   // coin ERM
foam = 0.3;                     // foam pad under the cell
bay_h = max(cell[2], motor_t) + foam + 0.2;

// ---------- Dock interface (shared with lift_dock_v2.scad) ----------
dock_pads = [[22.5, 14.2], [28.5, 14.2]];   // J1: VIN, GND (board bottom)
magnets   = [[18.9, 14.2], [31.6, 14.2]];   // 3 x 1.5 mm N52 discs
pin_hole  = 2.2;                // dock spring pins reach the pads through these
mag_d = 3.0;  mag_t = 1.5;  mag_skin = 0.4;

// ---------- Band (measure your band: width and thickness) ----------
strap_w = 20.0;  band_t = 1.8;
slot_w  = band_t + 0.6;         // gap the band passes through
bar_t   = 1.8;                  // the bar the band wraps around
lug_h   = 4.2;

// ---------- Shape ----------
clr = 0.25;  fit_clr = 0.1;  wall = 1.4;  floor_t = 1.0;  lid_t = 0.9;  lap_h = 1.0;
ledge_w = 0.8;  plan_r = 4.0;  top_r = 2.2;  bot_r = 1.2;
leds = [[28.2, 5.2], [28.2, 6.9]];   // D3 red (charging), D2 green (status)
logo_h = 6.0;  logo_depth = 0.3;
$fn = 48;

// ---------- Derived ----------
cav_l = pcb_l + 2*clr;  cav_w = pcb_w + 2*clr;
body_l = cav_l + 2*wall;  W = cav_w + 2*wall;
lug_l = slot_w + bar_t + 0.6;
L = body_l + 2*lug_l;
z_bay = floor_t;
z_pcb = z_bay + bay_h;          // board underside
z_top = z_pcb + pcb_t;
z_split = z_top;
H = z_top + parts_h + lid_t;

function pod(p) = [p[0] - pcb_l/2, pcb_w/2 - p[1]];   // board mm -> pod XY

module rr(l, w, r) {
    r2 = max(min(r, min(l, w)/2 - 0.01), 0.01);
    offset(r = r2) square([l - 2*r2, w - 2*r2], center = true);
}
function inset(z) =
    z < bot_r     ? bot_r - sqrt(max(bot_r*bot_r - (bot_r - z)*(bot_r - z), 0)) :
    z > H - top_r ? top_r - sqrt(max(top_r*top_r - (z - (H - top_r))*(z - (H - top_r)), 0)) : 0;
module pebble(l = body_l) {
    zs = concat([for (i = [0:6]) i*bot_r/6], [for (i = [0:10]) H - top_r + i*top_r/10]);
    hull() for (z = zs) translate([0, 0, z]) linear_extrude(0.001)
        rr(l - 2*inset(z), W - 2*inset(z), plan_r - inset(z));
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
// band lugs: a rounded bar at each end with a through-slot for the band
module lugs() {
    for (s = [-1, 1]) difference() {
        hull() {
            translate([s*(body_l/2 - 2), 0, 0]) linear_extrude(lug_h) rr(4, W - 1.0, 1.5);
            translate([s*(L/2 - bar_t/2), 0, bot_r]) rotate([90, 0, 0])
                cylinder(d = bar_t + 0.6, h = strap_w + 2.4, center = true);
            translate([s*(L/2 - bar_t/2), 0, lug_h - bar_t/2 - 0.3]) rotate([90, 0, 0])
                cylinder(d = bar_t + 0.6, h = strap_w + 2.4, center = true);
        }
        translate([s*(body_l/2 + slot_w/2 + 0.3), 0, lug_h/2])
            cube([slot_w, strap_w + 0.6, lug_h + 2], center = true);
    }
}
module base() {
    difference() {
        union() {
            intersection() { pebble(); translate([-L, -W, 0]) cube([2*L, 2*W, z_split]); }
            lugs();
            // half-lap tongue: inner half of the wall continues up
            difference() { cav(z_split - 0.01, z_split + lap_h, wall/2); cav(z_split - 1, z_split + lap_h + 1); }
        }
        cav(z_pcb, z_split + lap_h + 1);               // board pocket
        cav(z_bay, z_pcb + 0.01, -ledge_w);            // bay (leaves the ledge)
        for (p = dock_pads) { q = pod(p); translate([q[0], q[1], -1]) cylinder(d = pin_hole, h = floor_t + 2); }
        for (p = magnets) { q = pod(p); translate([q[0], q[1], mag_skin]) cylinder(d = mag_d + 0.15, h = 5); }
    }
    // locating ribs for the cell and the motor (0.8 mm high, on the floor)
    c = pod(cell_at);
    translate([c[0], c[1], z_bay - 0.01]) linear_extrude(0.8) difference() {
        rr(cell[0] + 0.6 + 1.2, cell[1] + 0.6 + 1.2, 1.0);
        rr(cell[0] + 0.6, cell[1] + 0.6, 0.6);
        translate([-cell[0]/2 - 2, -2]) square([3, 4]);   // gap for the cell's wires (PCM end)
    }
    m = pod(motor_at);
    translate([m[0], m[1], z_bay - 0.01]) linear_extrude(0.8) difference() {
        circle(d = motor_d + 0.4 + 1.2); circle(d = motor_d + 0.4);
        translate([0, -motor_d]) square([1.6, motor_d], center = true);   // wire exit
    }
    // collars around the magnet pockets (the pockets go above the floor)
    for (p = magnets) { q = pod(p); translate([q[0], q[1], z_bay - 0.01]) difference() {
        cylinder(d = mag_d + 1.4, h = mag_skin + mag_t - floor_t + 0.2);
        translate([0, 0, -1]) cylinder(d = mag_d + 0.15, h = 5);
    } }
}
module lid() {
    difference() {
        intersection() { pebble(); translate([-L, -W, z_split]) cube([2*L, 2*W, H]); }
        cav(z_split - 1, H - lid_t);                           // parts space
        cav(z_split - 1, z_split + lap_h + fit_clr, wall/2 + fit_clr);
        for (p = leds) { q = pod(p); translate([q[0], q[1], z_split]) cylinder(d = 1.2, h = H); }
        translate([-6, 0, H - logo_depth]) linear_extrude(1) r_logo(logo_h);
    }
}
if (part == "base") base();
if (part == "lid")  translate([0, 0, -z_split]) lid();
if (part == "all") { color("#111111") base(); color("#111111") translate([0, W + 8, -z_split]) lid(); }
if (part == "assembled") {
    color("#111111") base();
    color("#2d6a3e") translate([0, 0, z_pcb]) linear_extrude(pcb_t) rr(pcb_l, pcb_w, pcb_r);
    c = pod(cell_at); color("#b0b0b0") translate([c[0], c[1], z_bay + foam + cell[2]/2]) cube(cell, center = true);
    color("#1a1a1a", 0.85) lid();
}
echo(str("pod v2 size (mm): body ", body_l, " x ", W, " x ", H, " thick; with lugs ", L, " long"));

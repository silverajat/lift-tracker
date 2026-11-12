// Lift pod v2 case, Fitbit Air charger version.
//
// The board IS the pod's underside: it sits in a rebate in the bottom rim
// of a one-piece shell, so its black solder mask and the two gold charger
// contacts are the face that goes on the Fitbit Air charger (and on your
// wrist). Everything else stacks on top of the board, inside the shell:
//
//   shell top 1.2 mm  (engraved logo, 2 LED holes)
//   coin motor 2.5 on the Ebyte E73 module 3.0  |  LiPo 3.0 + foam on parts <= 1.1
//   board 0.8 mm, flush with the rim
//
// Print at JLC3DP: SLA black resin (8228 / "black resin"), sanded + mineral
// oil finish for a smooth, dry, Fitbit-like feel; or MJF PA12 nylon dyed
// black (tougher, slightly grainy).
//
// Assembly: solder the cell and motor wires, stick the cell and motor down
// with thin double-sided foam tape (the shell presses them down), glue the
// steel disc (kapton under it) at the marked circle, then press the board
// into the rim and run a thin bead of flexible glue (B-7000 / E6000) along
// the seam. Flexible glue can be cut to open the pod again.
//
// Sizes come from datasheet maximums + clearance, not a measured sample.
// Axes: band along X; antenna at -X (left end of the board, plastic
// only around it). Board coordinates (KiCad mm from the board's top-left,
// y down) map to the pod with pod(); positions match podv2/placement.py.

part = "shell";   // "shell", "assembled", "bottom"

// ---------- Board ----------
pcb_l = 41.0;  pcb_w = 18.0;  pcb_r = 2.0;  pcb_t = 0.8;
contacts = [[15.2, 9.0], [33.2, 9.0]];     // charger contacts (underside)
steel_at = [24.2, 9.0];                    // steel disc for the charger magnet
leds     = [[27.0, 16.9], [34.9, 1.2]];    // D2 green (status), D3 red (charging)

// ---------- On top of the board (datasheet maximums) ----------
cell = [20.5, 11.0, 3.0];       // 301120 LiPo, L x W x T max
cell_box = [19.9, 3.5, 40.4, 14.5];        // board coords of the cell outline
motor_d = 8.0;  motor_t = 2.5;  motor_at = [11.3, 9.0];
module_h = 3.0;  flash_h = 1.1;  foam = 0.3;  gap = 0.15;   // E73 height (3D model); tallest part under the cell
in_h = max(module_h + gap + motor_t, flash_h + gap + cell[2] + foam) + 0.1;

// ---------- Band (measure yours: width and thickness) ----------
strap_w = 20.0;  band_t = 1.8;
slot_w  = band_t + 0.6;
bar_t   = 1.8;
lug_h   = 4.2;
lug_side = 1.6;   // lug wall beside the strap slot

// ---------- Shape ----------
clr = 0.1;        // board in the rim: snug, glued
wall = 1.2;       // side wall (JLC3DP: walls > 1.2 mm)
stop_w = 0.8;     // corner stops the board pushes up against (JLC3DP: >= 0.8 mm)
stop_l = 2.4;     // ...only at the corners, where the board has no parts
top_t = 1.2;
top_r = 2.2;      // rounded top edge
in_r = 1.0;       // matching round on the inside, keeps the shell >= 1 mm there
chamfer = 0.3;    // small chamfer on the bottom outer edge
logo_h = 6.0;  logo_depth = 0.3;   // leaves 0.9 mm under the logo
$fn = 48;

// ---------- Derived ----------
cav_l = pcb_l + 2*clr;  cav_w = pcb_w + 2*clr;
body_l = cav_l + 2*wall;  W = cav_w + 2*wall;
plan_r = pcb_r + clr + wall;
H = pcb_t + in_h + top_t;
lug_l = slot_w + bar_t + 0.6;
L = body_l + 2*lug_l;

function pod(p) = [p[0] - pcb_l/2, pcb_w/2 - p[1]];   // board mm -> pod XY

module rr(l, w, r) {
    r2 = max(min(r, min(l, w)/2 - 0.01), 0.01);
    offset(r = r2) square([l - 2*r2, w - 2*r2], center = true);
}
function inset(z) =
    z < chamfer   ? chamfer - z :
    z > H - top_r ? top_r - sqrt(max(top_r*top_r - (z - (H - top_r))*(z - (H - top_r)), 0)) : 0;
module pebble() {
    zs = concat([0, chamfer], [for (i = [0:10]) H - top_r + i*top_r/10]);
    hull() for (z = zs) translate([0, 0, z]) linear_extrude(0.001)
        rr(body_l - 2*inset(z), W - 2*inset(z), plan_r - inset(z));
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
// band lugs: a rounded bar at each end with a through-slot; their underside
// is flush with the board, so the pod still sits flat on the charger
module lugs() {
    for (s = [-1, 1]) difference() {
        hull() {
            translate([s*(body_l/2 - 2), 0, 0]) linear_extrude(lug_h) rr(4, W - 1.0, 1.5);
            translate([s*(L/2 - bar_t/2), 0, bar_t/2 + 0.3]) rotate([90, 0, 0])
                cylinder(d = bar_t + 0.6, h = strap_w + 0.6 + 2*lug_side, center = true);
            translate([s*(L/2 - bar_t/2), 0, lug_h - bar_t/2 - 0.3]) rotate([90, 0, 0])
                cylinder(d = bar_t + 0.6, h = strap_w + 0.6 + 2*lug_side, center = true);
        }
        translate([s*(body_l/2 + slot_w/2 + 0.3), 0, lug_h/2])
            cube([slot_w, strap_w + 0.6, lug_h + 2], center = true);
    }
}
// L-shaped stops in the 4 corners, 0.8 mm in from the board edge, so the
// board can't be pushed into the shell (parts stay >= 0.9 mm from the corners)
module corner_stops() {
    for (sx = [-1, 1], sy = [-1, 1])
        translate([sx*(cav_l/2), sy*(cav_w/2), pcb_t]) mirror([sx < 0 ? 1 : 0, 0, 0]) mirror([0, sy < 0 ? 1 : 0, 0])
            translate([-stop_l, -stop_l, 0]) difference() {
                cube([stop_l, stop_l, in_h]);
                translate([-0.01, -0.01, -1]) cube([stop_l - stop_w + 0.01, stop_l - stop_w + 0.01, in_h + 2]);
            }
}
module cavity() {
    z0 = pcb_t - 0.01;  z1 = pcb_t + in_h;  ri = in_r;
    zs = concat([z0], [for (i = [0:8]) z1 - ri + i*ri/8]);
    hull() for (z = zs) translate([0, 0, z]) linear_extrude(0.001) {
        d = z <= z1 - ri ? 0 : ri - sqrt(max(ri*ri - (z - (z1 - ri))*(z - (z1 - ri)), 0));
        rr(cav_l - 2*d, cav_w - 2*d, max(pcb_r + clr - d, 0.3));
    }
}
module shell() {
    corner_stops();
    difference() {
        union() { pebble(); lugs(); }
        // rebate the board sits in (flush with the bottom)
        translate([0, 0, -1]) linear_extrude(pcb_t + 1) rr(cav_l, cav_w, pcb_r + clr);
        // parts space above the board (full board size; corner stops added below),
        // its top edge rounded so the shell stays >= 1 mm under the rounded outside
        cavity();
        // LED windows (fill with clear UV resin for a sealed light pipe)
        for (p = leds) { q = pod(p); translate([q[0], q[1], H - top_t - 1]) cylinder(d = 1.2, h = top_t + 2); }
        translate([-4, 0, H - logo_depth]) linear_extrude(1) r_logo(logo_h);
    }
}
module board() {
    color("#1b1b1b") linear_extrude(pcb_t) rr(pcb_l, pcb_w, pcb_r);
    for (p = contacts) { q = pod(p); color("gold") translate([q[0], q[1], -0.02]) linear_extrude(0.03) rr(4, 3, 1.5); }
}
if (part == "shell") shell();
if (part == "bottom") { color("#111111") shell(); board(); }   // view from below
if (part == "assembled") {
    color("#111111", 0.35) shell();
    board();
    color("#9a9a9a") translate([pod([9.3, 9])[0], pod([9.3, 9])[1], pcb_t + module_h/2]) cube([18, 13, module_h], center = true);
    m = pod(motor_at); color("#c0c0c0") translate([m[0], m[1], pcb_t + module_h + gap]) cylinder(d = motor_d, h = motor_t);
    c = pod([(cell_box[0] + cell_box[2])/2, (cell_box[1] + cell_box[3])/2]);
    color("#c9a227") translate([c[0], c[1], pcb_t + flash_h + gap + cell[2]/2]) cube(cell, center = true);
    s = pod(steel_at); color("#777") translate([s[0], s[1], pcb_t]) cylinder(d = 5, h = 0.5);
}

if (part == "exploded") {   // assembly order, bottom to top
    e = 6;
    board();
    color("#9a9a9a") translate([pod([9.3, 9])[0], pod([9.3, 9])[1], pcb_t + module_h/2]) cube([18, 13, module_h], center = true);
    color("#555") translate([pod([23.5, 3.2])[0], pod([23.5, 3.2])[1], pcb_t + 0.3]) cube([2, 3, 0.6], center = true);
    s = pod(steel_at); color("#c8c8c8") translate([s[0], s[1], pcb_t + e*0.6]) difference() { cylinder(d = 5, h = 0.5); translate([0,0,-1]) cylinder(d = 2.2, h = 3); }
    m = pod(motor_at); color("#d0d0d0") translate([m[0], m[1], pcb_t + module_h + e]) cylinder(d = motor_d, h = motor_t);
    c = pod([(cell_box[0] + cell_box[2])/2, (cell_box[1] + cell_box[3])/2]);
    color("#c9a227") translate([c[0], c[1], pcb_t + flash_h + e + cell[2]/2]) cube(cell, center = true);
    color("#111111", 0.55) translate([0, 0, 3*e]) shell();
}
echo(str("pod v2 size (mm): body ", body_l, " x ", W, " x ", H, " thick; with lugs ", L, " long; inside height above the board ", in_h));

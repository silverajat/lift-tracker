// Lift tracker gym pod v3 "pebble on a band" (prototype for D1–D7)
// Screenless pebble in the style of band trackers (Fitbit Air / Whoop):
// one continuous 20 mm loop band runs through a channel in the underside
// and through a closed keeper at each end, like a belt through belt loops.
// The channel is band-width, so the pod can't twist on the band.
//
// Lock: each keeper is slightly tighter than the band and has grip ridges,
// so the pod can't creep along the band. Fallback: an adhesive hook-velcro
// patch in the channel grabs the band's loop fabric.
//
// Threading: push the band's free end through one keeper, along the
// channel, out through the other keeper, then around the wrist, through
// the band's metal ring, and velcro it back on itself.
//
// Holds a Seeed XIAO nRF52840 Sense + a 302030-size protected LiPo
// (3 x 20 x 30 mm, ~150 mAh).
//
// Two printed parts plus a thin separator:
//   base      : band channel + keepers, floor, XIAO pocket, USB-C port
//   separator : clamps the XIAO down (rests on ledges + USB-C shell top)
//   lid       : domed cap holding the battery; half-lap joint onto the base
//
// Axes when worn: the strap runs along X (around the wrist); the USB-C port
// faces -Y. Keep the USB side pointing the same way every session.
//
// Measure your real parts with calipers and change the numbers below.

part = "all";   // "base", "separator", "lid", "all", "assembled", "exploded"

// ---------- XIAO nRF52840 Sense ----------
xiao_l       = 21.0;  // along the USB-C axis
xiao_w       = 17.8;
pcb_t        = 1.2;   // measure
usb_h        = 3.3;   // USB-C shell height above the PCB (measure)
usb_overhang = 0.6;   // measure

// ---------- Battery (302030) ----------
batt_l = 31.0;        // include the protection board at the lead end
batt_w = 21.0;
batt_h = 3.5;         // nominal 3.0 + swell/tape allowance

// ---------- Band ----------
strap_w  = 20.0;      // band width
band_t   = 1.8;       // band thickness (measure when it arrives)
band_clr = 0.2;       // side clearance per side in the channel (tight = no twist)
end_len  = 5.0;       // length of each end zone (holds the keeper)
keeper_l = 2.5;       // keeper bridge length along the band
keeper_t = 0.8;       // keeper bridge thickness (touches the wrist)
keeper_pinch = -0.2;  // keeper gap vs band thickness: negative = roomier, leaves room
                      // for the slot roof to sag when printed without supports
ridge_h  = 0.3;       // grip ridges on each keeper bridge: these do the gripping
ridge_n  = 3;

// ---------- Shape ----------
plan_r  = 6.0;        // corner radius seen from above (keepers need wall at the ends)
top_r   = 3.0;        // rounding of the top edge (the "dome")
bot_r   = 1.0;        // 45-degree chamfer on the bottom edge (prints cleanly, unlike a round)

// ---------- Print tuning ----------
clr     = 0.3;
fit_clr = 0.12;       // half-lap joint clearance (smaller = tighter lid)
wall    = 1.8;
floor_t = 1.0;
recess_d= 1.0;        // under the XIAO for solder joints / battery wires
sep_t   = 0.6;
lid_t   = 1.2;        // lid top thickness
lap_h   = 1.4;        // half-lap joint height
batt_gap= 0.3;

logo_h     = 9.0;   // height of the R on the lid
logo_depth = 0.5;   // engraving depth

$fn = 40;

// ---------- Derived ----------
pocket_x = xiao_w + 2*clr;                    // XIAO lies with USB along -Y
pocket_y = xiao_l + usb_overhang + 2*clr;
bay_l    = max(batt_l, pocket_x) + 2*clr;     // X
bay_w    = max(batt_w + 2*clr, pocket_y);     // Y

body_l = bay_l + 2*wall;
L      = body_l + 2*end_len;
W      = max(bay_w + 2*wall, strap_w + 2*2.6);

ch_w    = strap_w + 2*band_clr;               // band channel width
ch_h    = band_t + 0.3;                       // band channel depth
z_in    = ch_h;                               // electronics start above the channel
z_pcb   = z_in + floor_t + recess_d;
z_ledge = z_pcb + pcb_t + usb_h + 0.1;
z_split = z_ledge + sep_t;                    // base/lid parting line
H       = z_split + batt_h + batt_gap + lid_t;

// ---------- Pebble solid ----------
// "Split R" logo with a hidden A (R + A for Rajat), drawn in its source units (84 tall) and scaled to height h.
// Stem, bowl and leg are separate blocks with gaps between them.
module r_logo(h = 9) {
    k = h / 84;
    scale([k, -k]) translate([-548, -110]) {
        translate([510, 68]) square([15, 84]);                         // stem
        translate([533, 68]) square([17, 12]);                         // bowl top
        translate([533, 104]) square([17, 12]);                        // bowl bottom
        translate([550, 92]) difference() {                            // bowl curve
            circle(r = 24, $fn = 96); circle(r = 12, $fn = 96);
            translate([-30, -30]) square([30, 60]);
        }
        polygon([[543, 118], [559, 118], [586, 152], [570, 152]]);     // leg
        polygon([[533, 134], [547.7, 134], [555.6, 144], [533, 144]]); // crossbar: the hidden A
    }
}

module rr(l, w, r) {   // rounded rectangle, centred
    r2 = max(min(r, min(l, w)/2 - 0.01), 0.01);
    offset(r = r2) square([l - 2*r2, w - 2*r2], center = true);
}
function inset(z) =
    z < bot_r     ? bot_r - z :
    z > H - top_r ? top_r - sqrt(max(top_r*top_r - (z - (H - top_r))*(z - (H - top_r)), 0)) :
    0;
module pebble() {
    zs = concat([0, 0.15, 0.4, 0.7, 1.0], [for (i = [0:12]) H - top_r + i*top_r/12]);
    hull() for (z = zs)
        translate([0, 0, z]) linear_extrude(0.001)
            rr(L - 2*inset(z), W - 2*inset(z), plan_r - inset(z));
}

module bay_box(z0, z1, grow = 0) {
    translate([-(bay_l + 2*grow)/2, -(bay_w + 2*grow)/2, z0])
        cube([bay_l + 2*grow, bay_w + 2*grow, z1 - z0]);
}

module band_cut() {
    k0 = body_l/2 + 0.3;          // keeper starts just past the body wall
    k1 = k0 + keeper_l;
    // middle: open-bottom channel under the electronics
    translate([-k0, -ch_w/2, -1]) cube([2*k0, ch_w, ch_h + 1]);
    for (s = [-1, 1]) {
        // keeper tunnel: band rides over the bridge, pinched slightly
        translate([s > 0 ? k0 - 0.01 : -k1 - 0.01, -ch_w/2, keeper_t])
            cube([keeper_l + 0.02, ch_w, band_t - keeper_pinch]);
        // past the keeper: open again so the band can bend down around the wrist
        translate([s > 0 ? k1 : -L/2 - 1, -ch_w/2, -1])
            cube([L/2 - k1 + 1, ch_w, keeper_t + ch_h + 1]);
    }
}

// a stretch of band, for previews only
module band_preview() {
    bt = band_t;
    color("#5b6470") {
        translate([-body_l/2 - 0.3, -strap_w/2, 0.15]) cube([body_l + 0.6, strap_w, bt]);
        for (s = [-1, 1]) {
            translate([s*(body_l/2 + 0.3) + (s > 0 ? 0 : -keeper_l), -strap_w/2, keeper_t + 0.1])
                cube([keeper_l, strap_w, bt]);
            // curve down around an imaginary 32 mm-radius wrist
            translate([s*(L/2 - 1), 0, keeper_t + 0.1 + bt - 32]) rotate([90, 0, 0])
                rotate([0, 0, s > 0 ? 50 : 90]) rotate_extrude(angle = 40, $fn = 64)
                    translate([32 - bt, -strap_w/2]) square([bt, strap_w]);
        }
    }
}

module usb_cut() {   // fits a cable plug overmold; crosses the parting line
    usb_cz = z_pcb + pcb_t + usb_h/2;
    translate([0, -W/2 - 1, usb_cz]) rotate([-90, 0, 0]) hull()
        for (dx = [-2.75, 2.75]) translate([dx, 0, 0]) cylinder(d = 6.6, h = (W - bay_w)/2 + 1.5);
}

module base() {
    difference() {
        union() {
            intersection() { pebble(); translate([-L, -W, 0]) cube([2*L, 2*W, z_split]); }
            // grip ridges across each keeper bridge
            for (s = [-1, 1], i = [0 : ridge_n - 1]) {
                k0 = body_l/2 + 0.3;
                x = k0 + (i + 0.5) * keeper_l / ridge_n;
                translate([s*x - 0.25, -ch_w/2, keeper_t - 0.01]) cube([0.5, ch_w, ridge_h + 0.01]);
            }
            // inner half of the wall continues up as the half-lap tongue
            difference() {
                bay_box(z_split - 0.01, z_split + lap_h, wall/2);
                bay_box(z_split - 1, z_split + lap_h + 1);
            }
        }
        band_cut();
        // XIAO pocket, centred in X, against the USB (-Y) side
        translate([-pocket_x/2, -bay_w/2, z_pcb]) cube([pocket_x, pocket_y, z_ledge - z_pcb + 0.01]);
        // ledge level and above: full bay
        bay_box(z_ledge, z_split + lap_h + 1);
        // recess under the XIAO
        translate([-(pocket_x - 4)/2, -bay_w/2 + 2, z_in + floor_t]) cube([pocket_x - 4, pocket_y - 4, recess_d + 0.01]);
        // wire channel to the +X end and up to the ledge
        translate([0, -2, z_in + floor_t]) cube([bay_l/2 - 0.01, 4, recess_d + 0.01]);
        translate([bay_l/2 - 3, -2, z_in + floor_t]) cube([3, 4, z_ledge - z_in - floor_t + 0.01]);
        usb_cut();
    }
}

module separator() {
    sl = bay_l - 2*fit_clr - 0.2;
    sw = bay_w - 2*fit_clr - 0.2;
    difference() {
        translate([-sl/2, -sw/2, 0]) cube([sl, sw, sep_t]);
        translate([sl/2 - 3.5, -2.5, -1]) cube([4, 5, sep_t + 2]);   // battery wire notch
    }
}

module lid() {
    difference() {
        intersection() { pebble(); translate([-L, -W, z_split]) cube([2*L, 2*W, H]); }
        // battery cavity
        bay_box(z_split - 1, H - lid_t);
        usb_cut();
        // room for the base's tongue
        bay_box(z_split - 1, z_split + lap_h + fit_clr, wall/2 + fit_clr);
        // R logo engraved on top. It doubles as the orientation mark:
        // wear the pod so the R reads upright, and the USB-C port is at the
        // bottom of the R (toward -Y).
        translate([0, 0, H - logo_depth]) linear_extrude(1) r_logo(logo_h);
    }
}

if (part == "base")      base();
if (part == "separator") separator();
if (part == "lid")       translate([0, 0, -z_split]) lid();   // print as-is, dome up
if (part == "all") {
    base();
    translate([0, W + 8, -z_split]) lid();
    translate([0, -(W + 8), 0]) separator();
}
if (part == "assembled") {
    color("#2b2f36") base();
    band_preview();
    color("#9aa3ad") translate([0, 0, z_ledge]) separator();
    color("#3b4048") lid();
}
if (part == "exploded") {
    color("#2b2f36") base();
    color("#9aa3ad") translate([0, 0, z_ledge + 9]) separator();
    color("#3b4048") translate([0, 0, 18]) lid();
}

echo(str("Size (mm): ", L, " long x ", W, " wide x ", H, " thick"));

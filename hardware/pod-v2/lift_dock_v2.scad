// Lift pod v2 charging dock: a small puck the pod drops onto.
//   - 2 spring pins (Kinghelm KH-204065-DZ, 6.5 mm free height) on the dock
//     board poke up through the pod's floor holes and press on its charging
//     pads with ~0.7 mm preload.
//   - 2 magnets (3 x 1.5 mm N52) sit under the pod's magnets, with the poles
//     flipped from one side to the other: the pod only snaps on the right way
//     round, so VIN can never meet GND. The magnets pass through 3.4 mm holes
//     in the dock board.
//   - USB-C on the dock's side (power only, 5.1k CC pull-downs on the board).
//     The side is the one place the band never goes: the band leaves the pod
//     at both ends and drapes over the dock's ends.
//
// Shared numbers must match lift_pod_v2.scad and podv2/placement.py.
// Assembly: magnets into their pockets from below (check poles against the
// pod first), dock board in from below (pins up through the holes, USB-C into
// its opening), then glue on the bottom plate (part = "plate").

part = "dock";   // "dock", "plate", "all"

pcb_l = 33.0;  pcb_w = 17.0;
dock_pads = [[22.5, 14.2], [28.5, 14.2]];   // pod J1: VIN, GND
magnets   = [[18.9, 14.2], [31.6, 14.2]];
body_l = 36.3;  body_w = 20.3;  plan_r = 4.0;   // pod body (without lugs)
pod_floor = 1.0;  pod_bay = 3.5;   // pod: floor + bay up to its board's pads

// dock board (dockv2/dock.kicad_pcb): 32 x 25.4 x 1.0 mm
// board x = pod-frame x + 12, board y = 17.4 - pod-frame y
dpcb = [32.0, 25.4, 1.0];
dpcb_c = [-12 + 32/2, 17.4 - 25.4/2];   // board centre in the pod frame
usb_x = 16.0 - 12;                      // USB-C centre (pod frame x), mouth at +Y
usb_open = [9.4, 3.6];  usb_h = 3.26;

pin_free = 6.5;  preload = 0.7;
top_t = 1.2;  mag_d = 3.0;  mag_t = 1.5;  plate_t = 1.2;
rail_h = 1.2;  rail_w = 1.2;
$fn = 48;

function pod(p) = [p[0] - pcb_l/2, pcb_w/2 - p[1]];
module rr(l, w, r) { offset(r = r) square([l - 2*r, w - 2*r], center = true); }

// z = 0 is the dock's top face, where the pod's floor rests
z_pcb_top = (pod_floor + pod_bay) + preload - pin_free;   // -1.3
z_pcb_bot = z_pcb_top - dpcb[2];                           // board underside
z_bot     = z_pcb_bot - 0.6 - plate_t;
X0 = -body_l/2 - 5;  X1 = body_l/2 + 5;
Y0 = -body_w/2 - 3;  Y1 = dpcb_c[1] + dpcb[1]/2 + 1.6;
side_h = z_pcb_top + usb_h + 1.0;       // raised block over the USB-C, beside the pod

module outline(grow = 0) {
    translate([(X0 + X1)/2, (Y0 + Y1)/2]) rr(X1 - X0 + 2*grow, Y1 - Y0 + 2*grow, 4 + grow);
}
module dock() {
    difference() {
        union() {
            translate([0, 0, z_bot + plate_t]) linear_extrude(-z_bot - plate_t) outline();
            // raised side block (USB-C housing), doubles as the pod's side stop
            intersection() {
                translate([0, 0, -0.01]) linear_extrude(side_h + 0.01) outline();
                translate([X0 - 1, body_w/2 + 0.4, -1]) cube([X1 - X0 + 2, Y1, side_h + 2]);
            }
            // low rail on the other long side
            translate([0, -(body_w/2 + rail_w/2 + 0.4), 0]) linear_extrude(rail_h) square([body_l - 8, rail_w], center = true);
        }
        // cavity for the board (open at the bottom)
        translate([dpcb_c[0], dpcb_c[1], z_bot]) linear_extrude(z_pcb_top - z_bot)
            rr(dpcb[0] + 0.6, dpcb[1] + 0.6, 2.2);
        // room for the USB-C body above the board, inside the side block
        translate([usb_x, Y1 - 3.7 - 0.5, z_pcb_top + usb_h/2]) cube([9.2, 7.6, usb_h + 0.4], center = true);
        // USB-C opening in the side wall
        translate([usb_x, Y1 - 1, z_pcb_top + usb_h/2]) rotate([-90, 0, 0])
            linear_extrude(3) offset(r = 0.8) square([usb_open[0] - 1.6, usb_open[1] - 1.6], center = true);
        // pin holes; magnet pockets from below with a 0.4 mm top skin
        for (p = dock_pads) { q = pod(p); translate([q[0], q[1], -5]) cylinder(d = 2.3, h = 10); }
        for (p = magnets)   { q = pod(p); translate([q[0], q[1], -0.4 - mag_t - 3]) cylinder(d = mag_d + 0.15, h = mag_t + 3); }
    }
}
module plate() {   // bottom cover, glued on; recesses for 4 small rubber feet
    difference() {
        linear_extrude(plate_t) outline(-0.15);
        for (sx = [-1, 1], sy = [0, 1]) translate([sx*(body_l/2), Y0 + 3 + sy*(Y1 - Y0 - 6), plate_t - 0.4])
            cylinder(d = 6, h = 1);
    }
}
if (part == "dock") dock();
if (part == "plate") plate();
if (part == "all") { color("#111111") dock(); color("#222222") translate([0, -(Y1 - Y0) - 8, z_bot]) plate(); }
echo(str("dock: ", X1 - X0, " x ", Y1 - Y0, " mm, ", -z_bot + side_h, " mm tall at the USB side, ", -z_bot, " mm elsewhere"));

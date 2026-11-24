var STARTING_NUMBER = 1; //Starting number for each color
var MARKER_SIZE = 18; //Text size of the created marking
var USE_RECTANGLES = false; //Count rectangles instead of circles
var SKIP_WHITE = true; // Skip white circles since they are harder to see

// --- Label legibility ---
var BACKGROUND_OPACITY = 0.5; // White backdrop behind each number: 0 = none, 0.5 = plan shows through faded, 1 = solid white
var LABEL_BORDER = 1;         // Border width (points) around the number, in the marker color. 0 = no border
var BOLD_NUMBERS = true;      // Bold numbers read better at small zoom
var BOX_HEIGHT = 0.9;         // Box height as a fraction of MARKER_SIZE. Raise it if numbers get cut off at the bottom
var BOX_WIDTH = 0.56;         // Box width per digit as a fraction of MARKER_SIZE (a bold digit is ~0.56 wide)
var BOX_PADDING = 4;          // Extra room (points) beside the digits. Raise it if numbers wrap onto two lines

// --- Label placement ---
var AVOID_OVERLAP = true;     // Keep numbers off other circles and other numbers
var AVOID_PLAN_TEXT = true;   // Also keep numbers off the plan's own text (room names, device tags...)
var TEXT_AVOID_DISTANCE = 15; // How many points farther a number may move to avoid covering one word of plan text
var MAX_SEARCH = 40;          // Farthest (points) a number may move away from its circle
var CLARITY_MARGIN = 6;       // A number should be at least this many points closer to its own circle than to any other
var CLARITY_AVOID_DISTANCE = 20; // How many points farther a number may move to sit unmistakably next to its own circle
var LEADER_LINES = true;      // Draw a thin line from the circle to its number when the number had to move away
var LEADER_DISTANCE = 8;      // Draw a leader if the number ended up farther than this from its circle's edge,
                              // or if it ended up about as close to another circle (see CLARITY_MARGIN)
var LEADER_WIDTH = 2;         // Thickness (points) of the leader line
var LEADER_HALO = 0.8;        // White outline under the leader so it stands out from the plan: 0 = none, 1 = solid white
var CLEAR_PREVIOUS = false;   // Remove numbers/leaders from an earlier run first (only ones made by this script)
var AUTHOR = "Automated Script";
// Press CTRL+J to open debugger, paste everything, CTRL+A to select all, CTRL+Enter to run

//Get all colored circles and assign a number next to each of them

var doc = this;

// Where to try placing each number, as seen on screen, in order of preference: upper-right first,
// then the other sides. Must be defined up here, before the main loop below runs.
var SCREEN_DIRS = [[1, 1], [1, 0], [0, 1], [-1, 1], [-1, 0], [0, -1], [1, -1], [-1, -1]];

// Plans are often stored rotated in the PDF (page rotation 90/270). Annotation coordinates are in the
// page's unrotated space, so on those pages a number's on-screen width is its height in PDF space.
// These are set per page in the main loop.
var pageRotation = 0;
var DIRS = SCREEN_DIRS;

if (CLEAR_PREVIOUS) {
    var old = doc.getAnnots() || [];
    for (var i = 0; i < old.length; i++) {
        if (old[i].author === AUTHOR) old[i].destroy();
    }
}

var annots = doc.getAnnots() || [];
var pages = new Array(doc.numPages);
var color_count = {};

//Create empty arrays for each page
for (var i = 0; i < pages.length; i++) {
    pages[i] = [];
}

//Sort by creation date
annots.sort(function(a, b) {
    return a.creationDate - b.creationDate;
});

//Push each marker into its respective page
for (var i = 0; i < annots.length; i++) {
    if (isMarker(annots[i])) pages[annots[i].page].push(annots[i]);
}

//Go through each page, then each marker per page, then add their numbers
for (var i = 0; i < pages.length; i++) {
    if (pages[i].length === 0) continue;

    // Hard obstacles: every marker on the page, then each number as it is placed
    var obstacles = [];
    for (var j = 0; j < pages[i].length; j++) {
        obstacles.push(normRect(pages[i][j].rect));
    }
    var markers = obstacles.slice(0); // just the circles, for checking which circle a number looks closest to
    // Soft obstacles: the plan's own words
    var words = AVOID_PLAN_TEXT ? pageWords(i) : [];
    var bounds = pageBounds(i);
    pageRotation = getRotation(i);
    DIRS = toPageDirs(SCREEN_DIRS, pageRotation);

    for (var j = 0; j < pages[i].length; j++) {
        var annot = pages[i][j];
        var key = annot.fillColor.toString();
        if (color_count[key] == null) {
            color_count[key] = STARTING_NUMBER;
        }
        addColoredNumber(annot, color_count[key], obstacles, markers, words, bounds);
        color_count[key]++;
    }
}

function isMarker(annot) {
    var typeOk = USE_RECTANGLES ? annot.type === "Square" : annot.type === "Circle";
    if (!typeOk || !annot.fillColor || annot.fillColor[0] === "T") return false; // no fill = not a marker
    if (SKIP_WHITE && color.equal(annot.fillColor, color.white)) return false;
    return true;
}

function normRect(r) {
    return {
        l: Math.min(r[0], r[2]), b: Math.min(r[1], r[3]),
        r: Math.max(r[0], r[2]), t: Math.max(r[1], r[3])
    };
}

function getRotation(p) {
    try {
        return ((doc.getPageRotation(p) % 360) + 360) % 360;
    } catch (e) {
        return 0;
    }
}

// Turn on-screen directions into PDF-space directions for a page shown rotated clockwise by `rot`
function toPageDirs(dirs, rot) {
    var out = [];
    for (var k = 0; k < dirs.length; k++) {
        var vx = dirs[k][0], vy = dirs[k][1];
        if (rot === 90) out.push([-vy, vx]);
        else if (rot === 180) out.push([-vx, -vy]);
        else if (rot === 270) out.push([vy, -vx]);
        else out.push([vx, vy]);
    }
    return out;
}

// Page edges, so labels aren't pushed off the sheet. Skipped on rotated pages,
// where the page box and annotation coordinates don't line up the same way.
function pageBounds(p) {
    try {
        if (getRotation(p) !== 0) return null;
        return normRect(doc.getPageBox("Crop", p));
    } catch (e) {
        return null;
    }
}

// Bounding boxes of every word of real text on the page. Text that was flattened
// into linework in CAD isn't text anymore and can't be detected.
function pageWords(p) {
    var list = [];
    try {
        var n = doc.getPageNumWords(p);
        for (var w = 0; w < n; w++) {
            var quads = doc.getPageNthWordQuads(p, w);
            if (!quads) continue;
            for (var q = 0; q < quads.length; q++) {
                var qd = quads[q];
                list.push({
                    l: Math.min(qd[0], qd[2], qd[4], qd[6]), b: Math.min(qd[1], qd[3], qd[5], qd[7]),
                    r: Math.max(qd[0], qd[2], qd[4], qd[6]), t: Math.max(qd[1], qd[3], qd[5], qd[7])
                });
            }
        }
    } catch (e) {}
    return list;
}

function overlaps(a, b, pad) {
    return a.l < b.r + pad && a.r > b.l - pad && a.b < b.t + pad && a.t > b.b - pad;
}

function grow(r, by) {
    return { l: r.l - by, b: r.b - by, r: r.r + by, t: r.t + by };
}

// Box of size w x h placed `d` points away from the circle in direction dir ([-1|0|1, -1|0|1])
function boxAt(c, dir, d, w, h) {
    var cx = (c.l + c.r) / 2, cy = (c.b + c.t) / 2;
    var l = dir[0] > 0 ? c.r + d : (dir[0] < 0 ? c.l - d - w : cx - w / 2);
    var b = dir[1] > 0 ? c.t + d : (dir[1] < 0 ? c.b - d - h : cy - h / 2);
    return { l: l, b: b, r: l + w, t: b + h };
}

// Distance from the edge of a circle (given by its bounding box) to the nearest point of a box
function edgeGap(box, c) {
    var cx = (c.l + c.r) / 2, cy = (c.b + c.t) / 2;
    var radius = Math.min(c.r - c.l, c.t - c.b) / 2;
    var nx = Math.max(box.l, Math.min(cx, box.r)), ny = Math.max(box.b, Math.min(cy, box.t));
    return Math.max(0, Math.sqrt((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)) - radius);
}

// Try every direction at every distance up to MAX_SEARCH and keep the cheapest spot:
// each point of distance costs 1, each covered word of plan text costs TEXT_AVOID_DISTANCE,
// sitting about as close to another circle as to its own costs CLARITY_AVOID_DISTANCE,
// and covering another circle or number costs far more than any amount of moving.
function findSpot(c, w, h, obstacles, markers, words, bounds) {
    var area = grow(c, MAX_SEARCH + Math.max(w, h) + 2);
    var nearObs = [], nearWords = [], nearMarkers = [];
    for (var k = 0; k < obstacles.length; k++) {
        if (obstacles[k] !== c && overlaps(obstacles[k], area, 0)) nearObs.push(obstacles[k]);
    }
    for (var k = 0; k < markers.length; k++) {
        if (markers[k] !== c && overlaps(markers[k], grow(area, CLARITY_MARGIN), 0)) nearMarkers.push(markers[k]);
    }
    for (var k = 0; k < words.length; k++) {
        if (overlaps(words[k], area, 0)) nearWords.push(words[k]);
    }

    var best = null;
    var maxD = AVOID_OVERLAP ? MAX_SEARCH : 1;
    for (var d = 1; d <= maxD; d += 3) {
        for (var k = 0; k < DIRS.length; k++) {
            var box = boxAt(c, DIRS[k], d, w, h);
            if (bounds && (box.l < bounds.l || box.r > bounds.r || box.b < bounds.b || box.t > bounds.t)) continue;

            var cost = d + k * 0.5; // small nudge toward the preferred directions
            if (AVOID_OVERLAP) {
                for (var m = 0; m < nearObs.length; m++) {
                    if (overlaps(box, nearObs[m], 1)) cost += 1000;
                }
            }
            for (var m = 0; m < nearWords.length; m++) {
                if (overlaps(box, nearWords[m], 0)) cost += TEXT_AVOID_DISTANCE;
            }
            var ambiguous = isAmbiguous(box, c, nearMarkers);
            if (ambiguous) cost += CLARITY_AVOID_DISTANCE;

            if (!best || cost < best.cost) best = { box: box, cost: cost, ambiguous: ambiguous };
        }
        if (best && best.cost < d + 3) break; // the next ring out can't be cheaper than this
    }
    if (!best) {
        var box = boxAt(c, DIRS[0], 1, w, h);
        best = { box: box, ambiguous: isAmbiguous(box, c, nearMarkers) };
    }
    best.gap = edgeGap(best.box, c);
    return best;
}

// True if the box is about as close (within CLARITY_MARGIN) to some other circle as to its own
function isAmbiguous(box, c, others) {
    var own = edgeGap(box, c);
    for (var k = 0; k < others.length; k++) {
        if (edgeGap(box, others[k]) < own + CLARITY_MARGIN) return true;
    }
    return false;
}

function addColoredNumber(annot, num, obstacles, markers, words, bounds) {
    var c = obstacles[indexOfRect(obstacles, annot.rect)] || normRect(annot.rect);
    var text = num.toString();

    // Label size as seen on screen, kept tight so it covers as little of the plan as possible.
    // Acrobat keeps a margin inside the box and wraps the number onto a second line if it
    // doesn't fit, so the width needs a little slack beyond the digits themselves (BOX_PADDING).
    var w = text.length * MARKER_SIZE * BOX_WIDTH + 2 * LABEL_BORDER + BOX_PADDING;
    var h = MARKER_SIZE * BOX_HEIGHT + 2 * LABEL_BORDER + 2;
    // On a page shown sideways, on-screen width runs along the PDF's vertical axis
    if (pageRotation === 90 || pageRotation === 270) {
        var tmp = w; w = h; h = tmp;
    }

    var spot = findSpot(c, w, h, obstacles, markers, words, bounds);
    var box = spot.box;
    obstacles.push(box);

    // A leader when the number is far from its circle, or when it's not obvious which circle it belongs to
    if (LEADER_LINES && (spot.gap > LEADER_DISTANCE || spot.ambiguous)) {
        addLeader(annot, c, box);
    }

    // See-through backdrop: a separate faded white rectangle under the number, because
    // a FreeText annotation's opacity would fade the number along with its background.
    if (BACKGROUND_OPACITY > 0 && BACKGROUND_OPACITY < 1) {
        doc.addAnnot({
            type: "Square",
            page: annot.page,
            rect: [box.l, box.b, box.r, box.t],
            fillColor: color.white,
            strokeColor: color.transparent,
            width: 0,
            opacity: BACKGROUND_OPACITY,
            author: AUTHOR
        });
    }

    var newAnnot = doc.addAnnot({
        type: "FreeText",
        page: annot.page,
        rect: [box.l, box.b, box.r, box.t],
        contents: text,
        author: AUTHOR,
        fillColor: BACKGROUND_OPACITY >= 1 ? color.white : color.transparent,
        strokeColor: annot.fillColor,
        width: LABEL_BORDER,
        alignment: 1, // centered
        textSize: MARKER_SIZE,
        opacity: 1
    });

    // Text color, size and weight live in the rich text content
    if (newAnnot.richContents) {
        var spans = [];
        for each (var span in newAnnot.richContents) {
            span.textColor = annot.fillColor;
            span.textSize = MARKER_SIZE;
            span.alignment = "center";
            if (BOLD_NUMBERS) span.fontWeight = 700;
            spans.push(span);
        }
        newAnnot.richContents = spans;
    }
}

// The marker's own entry in the obstacle list, so it isn't counted as something to avoid
function indexOfRect(list, rect) {
    var r = normRect(rect);
    for (var k = 0; k < list.length; k++) {
        if (list[k].l === r.l && list[k].b === r.b && list[k].r === r.r && list[k].t === r.t) return k;
    }
    return -1;
}

// Thin line from the edge of the circle to the nearest point of its label
function addLeader(annot, c, box) {
    var cx = (c.l + c.r) / 2, cy = (c.b + c.t) / 2;
    var radius = Math.min(c.r - c.l, c.t - c.b) / 2;

    var ex = Math.max(box.l, Math.min(cx, box.r));
    var ey = Math.max(box.b, Math.min(cy, box.t));
    var dx = ex - cx, dy = ey - cy;
    var len = Math.sqrt(dx * dx + dy * dy);
    if (len <= radius) return;

    var sx = cx + dx / len * radius, sy = cy + dy / len * radius;
    var pad = LEADER_WIDTH * 2 + 2;
    var rect = [Math.min(sx, ex) - pad, Math.min(sy, ey) - pad, Math.max(sx, ex) + pad, Math.max(sy, ey) + pad];

    // White outline first, so the colored line drawn on top of it stands out from the plan's linework
    if (LEADER_HALO > 0) {
        doc.addAnnot({
            type: "Line",
            page: annot.page,
            rect: rect,
            points: [[sx, sy], [ex, ey]],
            strokeColor: color.white,
            width: LEADER_WIDTH * 2.5,
            opacity: LEADER_HALO,
            author: AUTHOR
        });
    }

    doc.addAnnot({
        type: "Line",
        page: annot.page,
        rect: rect,
        points: [[sx, sy], [ex, ey]],
        strokeColor: annot.fillColor,
        width: LEADER_WIDTH,
        opacity: 1,
        author: AUTHOR
    });
}

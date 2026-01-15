var STARTING_NUMBER = 1; //Starting number for each color
var MARKER_SIZE = 18; //Text size of the created marking
var USE_RECTANGLES = false; //Count rectangles instead of circles
var MARK_USING_BORDERS = false; //Marks based off border color instead of the main color
// Press CTRL+J to open debugger, paste everything, CTRL+A to select all, CTRL+Enter to run

//Get all colored circles and assign a number next to each of them

var annots = this.getAnnots();
var pages = new Array(this.numPages);
var color_count = {};

//Create empty arrays for each page
for(var i = 0; i < pages.length; i++) {
    pages[i] = [];
}

//Sort by creation date
annots.sort(function(a, b){
    return a.creationDate - b.creationDate;
});

//Push each annotation into its respective page
for (var i = 0; i < annots.length; i++) {
    pages[annots[i].page].push(annots[i]);
}

//Go through each page, then each annotation per page, then add their markings
for(var i = 0; i < pages.length; i++) {
    pages[i].forEach(function(annot) {
        if(checkAnnotType(annot)) {

            var color = annot.fillColor.toString();

            if(MARK_USING_BORDERS) {
                color = annot.strokeColor.toString();
            }

            if(color_count[color] == null) {
                color_count[color] = STARTING_NUMBER;             
            }

            addColoredNumber(annot, color_count[color]);
            color_count[color]++;
        }
    });
}

function checkAnnotType(annot) {
    if(USE_RECTANGLES) {
        return annot.type === "Square";
    }
    return annot.type === "Circle";
}

function addColoredNumber(annot, num) {   
    // Get the current position of the annotation (as a rectangle: [x1, y1, x2, y2])
    var rect = annot.rect;
    var used_color = annot.fillColor;
    if(MARK_USING_BORDERS) {
        used_color = annot.strokeColor;
    }

    // Calculate position 6 pixels above the center of the annot
    var xPos = (rect[0] + rect[2]) / 2; // X center
    var yPos = rect[1] + 6;             // 6 pixels above the top of the annot

    // Create a new FreeText annotation 6 pixels above, with white text, red background, and 50% opacity
    var newAnnot = this.addAnnot({
        type: "FreeText",               // FreeText annotation type
        page: annot.page,               // Same page as the annot
        rect: [xPos, yPos + 30, xPos, yPos + 30], // Text box with appropriate width and height
        contents: num.toString(),       // Just the number
        author: "Automated Script",      // Optional: Author name
        fillColor : color.transparent,
        opacity : 1,
        strokeColor : used_color,
    });


    // Modify text color in the rich text content
    if (newAnnot.richContents) {
        var spans = [];
        for each(var span in newAnnot.richContents) {
            span.textColor = used_color;
            span.textSize = MARKER_SIZE;
            spans.push(span);
        }
        newAnnot.richContents = spans;
    }
}

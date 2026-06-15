clearAFrame()

let startX = 0;
let startY = 0;
let startDistance = 0;

let parentObject

dblClick.foo = (event) => {

	var middle = event.ray.origin + event.ray.direction*0.2

    plane = aCreate("plane", "a-plane")
    var size = 0.5

    // put the plane where we clicked
    plane.position = middle
    plane.setAttribute("width", 1 * size)
    plane.setAttribute("height", 3/4  * size)
    plane.setAttribute("opacity", 1.0)
    plane.setAttribute("src", "/local/Desktop/ophelia.jpg")

    parentObject = plane

}
// Event-Listener für den Anfang der Berührung
window.document.addEventListener("touchstart", (event) => {
    if (event.touches.length === 1) {
        startX = event.touches[0].clientX;
        startY = event.touches[0].clientY;
    } else if (event.touches.length === 2) {
        startDistance = getDistance(event.touches[0], event.touches[1]);
   }
});

window.document.addEventListener("touchmove", (event) => {
    isRotating = true;
    if (event.touches.length === 1) {
        let currentX = event.touches[0].clientX;
        let deltaX = currentX - startX;
        let currentY = event.touches[0].clientY;
        let deltaY = currentY - startY;

        var r = parentObject.rotation
        parentObject.rotation = vec(r.x+ deltaY * 0.6, r.y + deltaX * 0.6, r.z);
        currentRotationX = parentObject.rotation.x;
        currentRotationY = parentObject.rotation.y;

        startX = currentX;
        startY = currentY;
    } else if (event.touches.length === 2) {

        let currentDistance = getDistance(event.touches[0], event.touches[1]);

        let scaleFactor = currentDistance / startDistance;

        if (scaleFactor !== 1) {
            let newScale = parentObject.scale.x * scaleFactor; // Skalierung basierend auf Veränderung des Abstands

            // Begrenze die Skalierung zwischen 0.5 und 3 (oder Werte deiner Wahl)
            newScale = Math.max(0.1, Math.min(10, newScale));
            currentScale = newScale;

            parentObject.scale.set(newScale, newScale, newScale); // Skaliere gleichmäßig in allen Achsen
        }

        // Aktualisiere den Startabstand
        startDistance = currentDistance;
    }
});

window.document.addEventListener("touchend", () => {
    window.setTimeout(() => {
        isRotating = false;
    }, 250);
});

getDistance = (touch1, touch2) => {
    let dx = touch2.clientX - touch1.clientX;
    let dy = touch2.clientY - touch1.clientY;
    return Math.sqrt(dx * dx + dy * dy);
}

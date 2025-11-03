
var lastDiv = null;

var notify = (text, cat = "") => {

        let newDiv;
        let noScroll = false;
        if (lastDiv && lastDiv.cat && lastDiv.cat.length > 0 && lastDiv.cat === cat) {
                newDiv = lastDiv;
                if (newDiv.innerHTML === text) return;
                noScroll = true;
        } else {
                newDiv = document.createElement("div");
                lastDiv = newDiv;
                lastDiv.cat = cat;
                noScroll = false;
        }

        newDiv.style.position = "absolute";
        newDiv.style.zIndex = 100;
        if (!noScroll) newDiv.style.opacity = 0;
        else {
                newDiv.innerHTML = text;
                newDiv.style.opacity = 1;
                return;
        }

        newDiv.style.maxWidth = "400px";
        newDiv.style.visibility = "hidden";
        newDiv.style.transition = "all 0.5s";
        newDiv.style.left = "5px";
        newDiv.classList = "bubble";
        newDiv.style.bottom = "5px";
        newDiv.innerHTML = text;
        newDiv.style.border = "1px solid black";
        document.body.appendChild(newDiv);

        const rect = newDiv.getBoundingClientRect();

        const n = 0;
        let q = document.getElementsByClassName("bubble").length;
        for (let a of document.getElementsByClassName("bubble")) {
                if (!noScroll)
                        if (a !== newDiv) {
                                a.style.bottom =
                                    parseInt(a.style.bottom.replace("px", "")) + rect.height + 3 + "px";
                                a.style.border = "none";
                                a.style.opacity = 0.3;
                        }

                if (q >= 5) {
                        a.style.visibility = "hidden";
                        setTimeout(function () {
                                if (a.parentNode)
                                        a.parentNode.removeChild(a)
                        }, 1000)
                        q--;
                }
        }

        newDiv.style.visibility = "visible";
        setTimeout(function () {
                newDiv.style.opacity = "1";
        }, 20);
        setTimeout(function () {
                newDiv.style.opacity = "0.5";
        }, 1000);
};

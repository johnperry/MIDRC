function setSizes() {
	var bodyPos = findObject(document.body);
	var dtObj = document.getElementById("datatable");
	var dtPos = findObject(dtObj);
	var dtH = (bodyPos.h - dtPos.y);
	dtObj.style.height = dtH;
}
window.onload = setSizes;
window.onresize = setSizes;

var lastClicked = 0;
var selectAllCB = 3;
var firstPtCB = 4;

function selectAll(event) {
	var x = document.getElementsByTagName("input");
	var sel = x[selectAllCB].checked;
	for (var i=firstPtCB; i<x.length; i++) x[i].checked = sel;
	count();
}

function selectRange(event) {
	var currentClicked = parseInt(event.target.id) + 1;
	if (event.shiftKey && (lastClicked >= firstPtCB)) {
		var x = document.getElementsByTagName("input");
		var sel = x[lastClicked].checked;
		if (currentClicked > lastClicked) {
			for (var i=lastClicked; i<=currentClicked; i++) x[i].checked = sel;
		}
		else if (currentClicked < lastClicked) {
			for (var i=currentClicked; i<=lastClicked; i++) x[i].checked = sel;
		}
	}
	lastClicked = currentClicked;
	count();
}

function count() {
	var x = document.getElementsByTagName("input");
	var ptCount = 0;
	var stCount = 0;
	var imCount = 0;
	for (var i=firstPtCB; i<x.length; i++) {
		if (x[i].checked) {
			var text = x[i].name.split(":");
			ptCount++;
			stCount += parseInt(text[1]);
			imCount += parseInt(text[2]);
		}
	}
	document.getElementById('nptCell').innerHTML = ptCount;
	document.getElementById('nstCell').innerHTML = stCount;
	document.getElementById('nimCell').innerHTML = imCount;
}

function exportImages(event) {
	var x = document.getElementsByTagName("input");
	var qs = "?export=";
	var first = true;
	var count = 0;
	for (var i=firstPtCB; i<x.length; i++) {
		if (x[i].checked) {
			count++;
			if (!first) qs += "|";
			qs += x[i].name.split(":")[0];
			first = false;
		}
	}
	if (count != 0) {
		var text = document.getElementById("commentCell").value;
		if ((text != null) && !((text=text.trim()) === "")) {
			window.open(qs + "&comment="+text, "_self");
		}
		else alert("A comment is required");
	}
	else alert("Nothing was selected");
}

function resetFailures(event) {
	var qs = "?reset=yes";
	window.open(qs, "_self");
}

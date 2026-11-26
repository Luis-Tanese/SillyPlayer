import QtQuick
import QtQuick.Window
import "ThumbnailQueue.js" as Requests

Image {
    id: thumbnail
    property var candidates: []
    property int attempt: 0
    property bool completed: false
    function loadCandidate() {
        // clear failed image state even when a recycled row receives the same URL
        deadline.stop();
        Requests.release(thumbnail);
        source = "";
        if (candidates.length > attempt) Requests.request(thumbnail);
    }
    function beginAttempt() {
        source = candidates.length > attempt ? candidates[attempt] : "";
        if (source.toString().length && status === Image.Loading) deadline.restart();
    }
    // avoid double-loading when initial candidates binding runs before Component.onCompleted
    onCandidatesChanged: { retry.stop(); attempt = 0; if (completed) loadCandidate(); }
    Component.onCompleted: { completed = true; loadCandidate(); }
    Component.onDestruction: Requests.release(thumbnail)
    sourceSize: width > 0 && height > 0
        ? Qt.size(Math.min(512, Math.max(128, Math.ceil(width * Screen.devicePixelRatio / 64) * 64)),
                  Math.min(512, Math.max(128, Math.ceil(height * Screen.devicePixelRatio / 64) * 64)))
        : Qt.size(256, 256)
    asynchronous: true
    fillMode: Image.PreserveAspectCrop
    onStatusChanged: {
        if (status === Image.Ready || status === Image.Error) { deadline.stop(); Requests.release(thumbnail); }
        if (status === Image.Error) {
            console.warn("Artwork decode/request failed; candidate", attempt + 1);
            if (attempt + 1 < candidates.length) retry.restart();
        }
    }
    Timer {
        id: deadline
        interval: 6000
        onTriggered: {
            thumbnail.source = "";
            Requests.release(thumbnail);
            if (thumbnail.attempt + 1 < thumbnail.candidates.length) { thumbnail.attempt++; thumbnail.loadCandidate(); }
        }
    }
    Timer {
        id: retry
        interval: 100
        onTriggered: if (thumbnail.status === Image.Error) { thumbnail.attempt++; thumbnail.loadCandidate(); }
    }
}

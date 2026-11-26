import QtQuick
import QtQuick.Controls
import QtQuick.Window

Rectangle {
    id: art
    property string source: ""
    readonly property bool fillSquare: (typeof app !== "undefined" && app && app.settings && app.settings.cropArtwork !== undefined) ? !!app.settings.cropArtwork : true
    // pick generous DPR-aware tiers so center-cropped 16:9 artwork stays crisp without excessive pixmap cache churn
    readonly property int decodeSize: {
        const px = Math.max(width, height) * Screen.devicePixelRatio;
        return px > 256 ? 1024 : px > 128 ? 512 : 256;
    }
    readonly property bool isLocalSource: !!art.source && !art.source.startsWith("http://") && !art.source.startsWith("https://") && !art.source.startsWith("image://") && !art.source.startsWith("qrc:/")
    readonly property real radiusFraction: Math.min(0.5, art.radius / Math.max(1, Math.min(art.width > 0 ? art.width : art.implicitWidth, art.height > 0 ? art.height : art.implicitHeight)))
    readonly property string resolvedSource: {
        if (!art.source) return "";
        if (art.isLocalSource && typeof artProviderReady !== "undefined" && artProviderReady) {
            return "image://art/" + (art.fillSquare ? "1" : "0") + "/" + art.radiusFraction.toFixed(4) + "/" + encodeURIComponent(art.source);
        }
        return art.source;
    }
    property bool interactive: false
    property string actionGlyph: "play"
    signal clicked(int modifiers)
    implicitWidth: 44
    implicitHeight: 44
    color: (art.fillSquare && art.isLocalSource && img.status === Image.Ready) ? "transparent" : Theme.hover
    radius: 12
    clip: true
    Image {
        id: img
        anchors.fill: parent
        source: art.resolvedSource
        sourceSize: Qt.size(art.decodeSize, art.decodeSize)
        fillMode: art.fillSquare ? Image.PreserveAspectCrop : Image.PreserveAspectFit
        smooth: true
        mipmap: true
        asynchronous: true
    }
    AppIcon {
        anchors.centerIn: parent
        visible: !art.source
        glyph: "music-2"
        color: Theme.text
        opacity: 0.5
    }
    Loader {
        anchors.fill: parent
        active: mouse.containsMouse && art.interactive
        sourceComponent: Rectangle {
            color: "#50000000"
            radius: art.radius
            AppIcon {
                anchors.centerIn: parent
                glyph: art.actionGlyph
                color: Theme.text
            }
        }
    }
    MouseArea {
        id: mouse
        anchors.fill: parent
        enabled: art.interactive
        hoverEnabled: true
        cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
        onClicked: mouse => art.clicked(mouse.modifiers)
    }
}

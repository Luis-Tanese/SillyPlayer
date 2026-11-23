import QtQuick
import QtQuick.Controls
import QtQuick.Window

Rectangle {
    id: art
    property string source: ""
    readonly property int decodeSize: Math.min(1024, Math.max(64, Math.ceil(Math.max(width, height) * Screen.devicePixelRatio / 64) * 64))
    property bool interactive: false
    property string actionGlyph: "play"
    signal clicked(int modifiers)
    implicitWidth: 44
    implicitHeight: 44
    color: Theme.hover
    radius: 12
    clip: true
    Image {
        anchors.fill: parent
        source: art.source
        sourceSize: Qt.size(art.decodeSize, art.decodeSize)
        fillMode: Image.PreserveAspectFit
        asynchronous: true
    }
    FlatButton {
        anchors.centerIn: parent
        visible: !art.source
        glyph: "music-2"
        enabled: false
        opacity: 0.5
    }
    Rectangle {
        anchors.fill: parent
        color: "#50000000"
        radius: art.radius
        visible: mouse.containsMouse && art.interactive
    }
    FlatButton {
        anchors.centerIn: parent
        glyph: art.actionGlyph
        visible: mouse.containsMouse && art.interactive
        enabled: false
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

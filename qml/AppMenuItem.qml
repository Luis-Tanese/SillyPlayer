import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

MenuItem {
    id: control
    implicitHeight: 37
    leftPadding: 12
    rightPadding: 12
    property string glyph: ""
    contentItem: RowLayout {
        spacing: 10
        AppIcon { glyph: control.glyph; visible: !!glyph; opacity: control.enabled ? 1 : 0.5 }
        Label { text: control.text; color: control.enabled ? Theme.text : Theme.muted; font.pixelSize: 13; elide: Text.ElideRight; Layout.fillWidth: true }
    }
    background: Rectangle {
        radius: 5
        color: control.highlighted ? Theme.hover : "transparent"
    }
}

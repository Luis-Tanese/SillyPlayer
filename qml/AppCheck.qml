import QtQuick
import QtQuick.Controls

CheckBox {
    id: control
    spacing: 10
    implicitHeight: 36
    indicator: Rectangle {
        x: control.leftPadding
        y: (control.height - height) / 2
        width: 18
        height: 18
        radius: 5
        color: control.checked ? Theme.accent : Theme.surface
        border.color: control.activeFocus ? Theme.accent : Theme.rail
        AppIcon {
            anchors.centerIn: parent
            width: 13
            height: 13
            glyph: "check"
            color: Theme.base
            visible: control.checked
        }
    }
    contentItem: Text {
        text: control.text
        font.pixelSize: 13
        color: Theme.text
        leftPadding: control.indicator.width + control.spacing
        verticalAlignment: Text.AlignVCenter
    }
}

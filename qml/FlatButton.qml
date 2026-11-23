import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ToolButton {
    id: control
    property string glyph: ""
    property string hint: ""
    property bool navigation: false
    property bool primary: false
    property bool outlined: false
    property bool danger: false
    Layout.minimumWidth: navigation ? implicitWidth : 0
    implicitHeight: navigation ? 40 : 36
    implicitWidth: Math.max(36, Math.ceil(labelMetrics.advanceWidth) + leftPadding + rightPadding + (glyph ? icon.width + (text ? spacing : 0) : 0))
    TextMetrics { id: labelMetrics; text: control.text; font: control.font }
    leftPadding: text ? 14 : 8
    rightPadding: text ? 14 : 8
    spacing: 8
    icon.source: glyph ? "qrc:/assets/icons/" + glyph + ".svg" : ""
    icon.width: 18
    icon.height: 18
    icon.color: primary ? Theme.base : danger ? Theme.danger : checked ? Theme.accent : Theme.text
    palette.buttonText: primary ? Theme.base : danger ? Theme.danger : checked ? Theme.accent : Theme.text
    font.pixelSize: 13
    opacity: enabled ? 1 : 0.4
    background: Rectangle {
        radius: 8
        border.width: control.outlined && !control.primary ? 1 : 0
        border.color: control.danger ? Qt.alpha(Theme.danger, 0.45) : control.hovered ? Qt.alpha(Theme.accent, 0.45) : Theme.separator
        color: control.primary
               ? (control.down ? Qt.darker(Theme.accent, 1.12) : Theme.accent)
               : control.down
                 ? Theme.hover
                 : control.hovered
                   ? Qt.alpha(control.danger ? Theme.danger : Theme.hover, control.danger ? 0.16 : 0.65)
                   : control.checked && !control.navigation
                     ? Qt.alpha(Theme.accent, 0.12)
                     : control.outlined
                       ? Qt.alpha(control.danger ? Theme.danger : Theme.hover, control.danger ? 0.08 : 0.35)
                       : "transparent"
        Rectangle {
            visible: control.navigation && control.checked
            anchors.bottom: parent.bottom
            anchors.horizontalCenter: parent.horizontalCenter
            width: parent.width - 24
            height: 2
            radius: 1
            color: Theme.accent
        }
    }
    ToolTip.visible: hovered && hint.length > 0
    ToolTip.text: hint
    Accessible.name: hint || text || glyph
}

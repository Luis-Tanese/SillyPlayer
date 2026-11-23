import QtQuick
import QtQuick.Controls

Slider {
    id: control
    property int decimals: 0
    property string suffix: ""
    implicitWidth: horizontal ? 112 : 30
    implicitHeight: horizontal ? 30 : 160
    padding: 7
    hoverEnabled: true
    background: Rectangle {
        x: control.horizontal ? control.leftPadding : (control.width - width) / 2
        y: control.horizontal ? (control.height - height) / 2 : control.topPadding
        width: control.horizontal ? control.availableWidth : 4
        height: control.horizontal ? 4 : control.availableHeight
        radius: 2
        color: Theme.rail
        Rectangle {
            y: control.horizontal ? 0 : control.visualPosition * parent.height
            width: control.horizontal ? control.position * parent.width : parent.width
            height: control.horizontal ? parent.height : control.position * parent.height
            radius: 2
            color: control.enabled ? Theme.accent : Theme.muted
        }
    }
    handle: Rectangle {
        x: control.horizontal ? control.leftPadding + control.visualPosition * (control.availableWidth - width) : (control.width - width) / 2
        y: control.horizontal ? (control.height - height) / 2 : control.topPadding + control.visualPosition * (control.availableHeight - height)
        width: 12
        height: 12
        radius: 6
        color: control.enabled ? Theme.accent : Theme.muted
        border.width: control.visualFocus ? 2 : 0
        border.color: Theme.text
    }
    ToolTip.visible: hovered || pressed
    ToolTip.text: value.toFixed(decimals) + suffix
}

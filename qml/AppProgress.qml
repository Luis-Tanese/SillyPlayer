import QtQuick
import QtQuick.Controls

ProgressBar {
    id: control
    implicitHeight: 5
    padding: 0
    background: Rectangle {
        implicitHeight: 5
        radius: 2.5
        color: Theme.rail
    }
    contentItem: Item {
        clip: true
        Rectangle {
            id: fill
            width: control.indeterminate ? parent.width * 0.22 : parent.width * control.position
            height: parent.height
            radius: 2.5
            color: Theme.accent
            SequentialAnimation on x  {
                running: control.indeterminate && control.visible
                loops: Animation.Infinite
                NumberAnimation {
                    from: -control.width * 0.22
                    to: control.width
                    duration: 1300
                    easing.type: Easing.InOutSine
                }
            }
            Binding {
                target: fill
                property: "x"
                value: 0
                when: !control.indeterminate
                restoreMode: Binding.RestoreNone
            }
        }
    }
}

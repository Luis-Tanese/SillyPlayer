import QtQuick
import QtQuick.Controls

BusyIndicator {
    id: control
    implicitWidth: 28
    implicitHeight: 28
    contentItem: Item {
        id: spinner
        opacity: control.running ? 1 : 0
        Repeater {
            model: 8
            Rectangle {
                required property int index
                x: spinner.width / 2 + Math.cos(index * Math.PI / 4) * spinner.width * 0.34 - 1.5
                y: spinner.height / 2 + Math.sin(index * Math.PI / 4) * spinner.height * 0.34 - 1.5
                width: 3
                height: 3
                radius: 1.5
                color: Theme.accent
                opacity: 0.2 + index / 10
            }
        }
        RotationAnimator on rotation  {
            from: 0
            to: 360
            duration: 850
            loops: Animation.Infinite
            running: control.running && control.visible
        }
    }
}

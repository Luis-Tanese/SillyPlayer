import QtQuick
import QtQuick.Controls

AppSlider {
    id: slider
    required property var backend
    from: 0
    to: 100
    stepSize: 1
    snapMode: Slider.SnapAlways
    onMoved: backend.volume = value
    Binding {
        target: slider
        property: "value"
        value: slider.backend.volume
        when: !slider.pressed
        restoreMode: Binding.RestoreNone
    }
}

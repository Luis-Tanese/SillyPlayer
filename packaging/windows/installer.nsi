Unicode True
!include "MUI2.nsh"
!include "x64.nsh"
!include "LogicLib.nsh"
Name "SillyPlayer"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\SillyPlayer"
InstallDirRegKey HKCU "Software\SillyPlayer" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
SetCompressorDictSize 32
VIProductVersion "1.2.1.0"
VIAddVersionKey "ProductName" "SillyPlayer"
VIAddVersionKey "FileDescription" "SillyPlayer installer"
VIAddVersionKey "FileVersion" "1.2.1"
!define MUI_ICON "${PROJECT}\assets\sillyplayer.ico"
!define MUI_UNICON "${PROJECT}\assets\sillyplayer.ico"
!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function CheckRunning
 System::Call 'kernel32::OpenMutexW(i 0x00100000, i 0, w "Local\SillyPlayer.ProgramRunning") p.r0'
 ${If} $0 != 0
  System::Call 'kernel32::CloseHandle(p r0)'
  MessageBox MB_OK|MB_ICONEXCLAMATION "Exit SillyPlayer from its tray menu before installing or removing it."
  Abort
 ${EndIf}
FunctionEnd
Function .onInit
 ${IfNot} ${RunningX64}
  MessageBox MB_OK|MB_ICONSTOP "SillyPlayer requires 64-bit Windows 10 or newer."
  Abort
 ${EndIf}
 SetRegView 64
 SetShellVarContext current
 Call CheckRunning
FunctionEnd
Function un.onInit
 SetRegView 64
 SetShellVarContext current
 System::Call 'kernel32::OpenMutexW(i 0x00100000, i 0, w "Local\SillyPlayer.ProgramRunning") p.r0'
 ${If} $0 != 0
  System::Call 'kernel32::CloseHandle(p r0)'
  MessageBox MB_OK|MB_ICONEXCLAMATION "Exit SillyPlayer from its tray menu first."
  Abort
 ${EndIf}
FunctionEnd
Section "SillyPlayer (required)"
 SectionIn RO
 IfFileExists "$INSTDIR\Uninstall.exe" 0 fresh
  ExecWait '"$INSTDIR\Uninstall.exe" /S _?=$INSTDIR'
 fresh:
 SetOutPath "$INSTDIR\app"
 File /r "${PAYLOAD}\*"
 SetOutPath "$INSTDIR"
 WriteUninstaller "$INSTDIR\Uninstall.exe"
 WriteRegStr HKCU "Software\SillyPlayer" "InstallDir" "$INSTDIR"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "DisplayName" "SillyPlayer"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "DisplayVersion" "1.1.0"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "Publisher" "SillyPlayer"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "InstallLocation" "$INSTDIR"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "DisplayIcon" "$INSTDIR\app\SillyPlayer.exe"
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
 WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
 WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "NoModify" 1
 WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "NoRepair" 1
 WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer" "EstimatedSize" ${SIZE_KIB}
 CreateShortcut "$SMPROGRAMS\SillyPlayer.lnk" "$INSTDIR\app\SillyPlayer.exe"
SectionEnd
Section /o "Desktop shortcut"
 CreateShortcut "$DESKTOP\SillyPlayer.lnk" "$INSTDIR\app\SillyPlayer.exe"
SectionEnd
Section "Uninstall"
 ; Generated paths include only files that belong to this payload, never library/user data.
 !include "${REMOVE_FILES}"
 Delete "$SMPROGRAMS\SillyPlayer.lnk"
 Delete "$DESKTOP\SillyPlayer.lnk"
 DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\SillyPlayer"
 DeleteRegKey HKCU "Software\SillyPlayer"
 Delete "$INSTDIR\Uninstall.exe"
 RMDir "$INSTDIR"
 SetErrorLevel 0
SectionEnd

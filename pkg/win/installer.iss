[Setup]
AppName=PC Bio Unlock
AppVerName=PC Bio Unlock
WizardStyle=modern
DefaultDirName={autopf}\PCBioUnlock
DefaultGroupName=PC Bio Unlock
UninstallDisplayIcon={app}\pcbu_desktop.exe
Compression=lzma2
SolidCompression=yes
OutputDir=..\build\
PrivilegesRequired=admin
ArchitecturesAllowed={#GetEnv('ARCH')}
ArchitecturesInstallIn64BitMode={#GetEnv('ARCH')}
;ArchitecturesAllowed=arm64
;ArchitecturesInstallIn64BitMode=arm64

[Files]
Source: "..\build\installer_dir\*"; DestDir: "{app}"; Flags: recursesubdirs
Source: "..\build\vcredist.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[UninstallDelete]
Type: files; Name: "{win}\System32\win-pcbiounlock.dll"

[Icons]
Name: "{group}\PC Bio Unlock"; Filename: "{app}\pcbu_desktop.exe"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PCBioUnlockCloud"; ValueData: """{app}\pcbu_desktop.exe"" --background"; Flags: uninsdeletevalue

[Run]
Filename: "{tmp}\vcredist.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "Installing Microsoft Visual C++ Runtime..."; Flags: waituntilterminated
Filename: "{app}\pcbu_desktop.exe"; Description: "Launch PC Bio Unlock"; Verb: runas; Flags: postinstall nowait skipifsilent runascurrentuser shellexec

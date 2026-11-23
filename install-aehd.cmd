@echo off
chcp 65001 >nul
net session >nul 2>&1
if errorlevel 1 (
    echo [!] Запустите этот файл от имени администратора:
    echo     ПКМ по install-aehd.cmd -^> "Запуск от имени администратора"
    echo.
    pause
    exit /b 1
)

set "AEHD_DIR=%LOCALAPPDATA%\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver"
set "EMU=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"

echo === Установка Google Android Emulator Hypervisor Driver (AEHD) ===
cd /d "%AEHD_DIR%"

sc query aehd >nul 2>&1 && (
    sc stop aehd >nul 2>&1
    sc delete aehd >nul 2>&1
)

echo [1/3] Установка драйвера из INF...
RUNDLL32.EXE SETUPAPI.DLL,InstallHinfSection DefaultInstall 132 "%AEHD_DIR%\aehd.Inf"
sc query aehd >nul 2>&1
if errorlevel 1 (
    echo Пробуем pnputil...
    pnputil /add-driver "%AEHD_DIR%\aehd.Inf" /install
)

echo [2/3] Запуск службы aehd...
sc start aehd
sc query aehd

echo [3/3] Проверка эмулятора...
"%EMU%" -accel-check
echo.
echo Готово! Можно закрывать это окно.
pause

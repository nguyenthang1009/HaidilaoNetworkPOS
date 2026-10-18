@echo off
rem CHI CHAY TREN MAY CHU (May chay [1] May chu). Chuot phai file nay -> Run as administrator.
rem Mo cong TCP 8888 tren Windows Firewall de cac laptop khac ket noi vao Server.

net session >nul 2>nul
if errorlevel 1 (
    echo [LOI] Can quyen Administrator. Hay chuot phai file nay va chon: Run as administrator
    pause
    exit /b 1
)

netsh advfirewall firewall delete rule name="Haidilao POS 8888" >nul 2>nul
netsh advfirewall firewall add rule name="Haidilao POS 8888" dir=in action=allow protocol=TCP localport=8888
if errorlevel 1 (
    echo [LOI] Khong mo duoc cong 8888.
) else (
    echo Da mo cong TCP 8888. Nho dat mang Wi-Fi o che do Private.
)
pause

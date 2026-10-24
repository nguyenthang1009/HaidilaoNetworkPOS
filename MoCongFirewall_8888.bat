@echo off
rem CHI CHAY TREN MAY CHU. Chuot phai file nay -> Run as administrator.
rem Mo 2 cong tren Windows Firewall:
rem   - TCP 8888: cac may Le tan / Bep / Tablet ket noi vao Server
rem   - UDP 8889: cac may tu tim thay Server trong mang Wi-Fi (khong can nhap IP)

net session >nul 2>nul
if errorlevel 1 (
    echo [LOI] Can quyen Administrator. Hay chuot phai file nay va chon: Run as administrator
    pause
    exit /b 1
)

netsh advfirewall firewall delete rule name="Haidilao POS 8888" >nul 2>nul
netsh advfirewall firewall delete rule name="Haidilao POS 8889 UDP" >nul 2>nul
netsh advfirewall firewall add rule name="Haidilao POS 8888" dir=in action=allow protocol=TCP localport=8888
if errorlevel 1 (
    echo [LOI] Khong mo duoc cong TCP 8888.
    pause
    exit /b 1
)
netsh advfirewall firewall add rule name="Haidilao POS 8889 UDP" dir=in action=allow protocol=UDP localport=8889
if errorlevel 1 (
    echo [LOI] Khong mo duoc cong UDP 8889.
    pause
    exit /b 1
)
echo Da mo cong TCP 8888 va UDP 8889. Nho dat mang Wi-Fi o che do Private.
pause

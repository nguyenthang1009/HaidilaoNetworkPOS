@echo off
rem Chay chuong trinh (man hinh chon: May chu / Le tan / Bep / Tablet). Khong can nhap IP may chu.
cd /d "%~dp0dist"

where java >nul 2>nul
if errorlevel 1 (
    echo [LOI] May nay chua cai Java. Hay cai Java 17 tro len roi chay lai.
    pause
    exit /b 1
)

if not exist "HaidilaoNetworkPOS.jar" (
    echo [LOI] Khong tim thay dist\HaidilaoNetworkPOS.jar. Hay Clean and Build trong NetBeans truoc.
    pause
    exit /b 1
)

start "" javaw -jar HaidilaoNetworkPOS.jar

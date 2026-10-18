@echo off
rem Xuat database haidilao_pos_db ra file database\haidilao_pos_db.sql (chay truoc khi nen project mang di)
rem Can Laragon dang Start (MySQL dang chay).

set MYSQLDUMP=D:\laragon\bin\mysql\mysql-8.0.30-winx64\bin\mysqldump.exe
if not exist "%MYSQLDUMP%" (
    echo [LOI] Khong tim thay %MYSQLDUMP%
    echo Hay sua dong "set MYSQLDUMP=..." cho dung thu muc MySQL cua Laragon tren may nay.
    pause
    exit /b 1
)

cd /d "%~dp0"
if not exist database mkdir database
"%MYSQLDUMP%" -u root --host=127.0.0.1 --databases haidilao_pos_db --default-character-set=utf8mb4 --hex-blob --single-transaction --skip-lock-tables --result-file=database\haidilao_pos_db.sql
if errorlevel 1 (
    echo [LOI] Xuat that bai. Kiem tra Laragon da Start chua.
) else (
    echo Da xuat database ra database\haidilao_pos_db.sql
)
pause

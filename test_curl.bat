@echo off
set "URL=https://upos-sz-estgcos.bilivideo.com/upgcxcode/02/60/38688196002/38688196002-1-192.mp4?e=ig8euxZM2rNcNbRVhwdVhwdlhWdVhwdVhoNvNC8BqJIzNbfq9rVEuxTEnE8L5F6VnEsSTx0vkX8fqJeYTj_lta53NCM=&oi=1887184075&mid=0&deadline=1780123942&trid=fd345554e50e42c78331a603adf0effh&gen=playurlv3&os=estgcos&og=cos&nbs=1&uipk=5&platform=html5&upsig=f4f12c54ca618fff4617bcc9ca8a1db6&uparams=e,oi,mid,deadline,trid,gen,os,og,nbs,uipk,platform&bvc=vod&nettype=0&bw=762627&lrs=0&agrr=0&buvid=&build=0&dl=0&f=h_0_0&orderid=0,1"

echo =======================================================
echo Test 1: No Custom Headers (Native Curl UA, No Referer)
echo =======================================================
curl.exe -I -s "%URL%" | findstr "HTTP/"

echo =======================================================
echo Test 2: Custom Chrome UA + Bilibili Referer
echo =======================================================
curl.exe -I -s -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" -H "Referer: https://www.bilibili.com/" "%URL%" | findstr "HTTP/"

echo =======================================================
echo Test 3: MPV Native User-Agent + No Referer
echo =======================================================
curl.exe -I -s -H "User-Agent: mpv v0.34.0" "%URL%" | findstr "HTTP/"

echo =======================================================
echo Test 4: MPV Native User-Agent + Bilibili Referer
echo =======================================================
curl.exe -I -s -H "User-Agent: mpv v0.34.0" -H "Referer: https://www.bilibili.com/" "%URL%" | findstr "HTTP/"

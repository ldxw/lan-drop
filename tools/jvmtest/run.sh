#!/bin/sh
set -e
if [ ! -d /opt/kotlinc ]; then
  mkdir -p /opt && cd /opt && jar xf /work/kotlin.zip && cd /work
  chmod -R +x /opt/kotlinc/bin
fi
/opt/kotlinc/bin/kotlinc src/HttpServer.kt src/Harness.kt -include-runtime -d /tmp/test.jar 2>&1 | grep -v "^warning:" || true
java -jar /tmp/test.jar /tmp/recv /work/page.html 18080 > /tmp/server.log 2>&1 &
for i in $(seq 1 30); do grep -q STARTED /tmp/server.log && break; sleep 1; done
echo "--- 1) 首页 ---"
curl -s -o /tmp/idx.html -w "  HTTP %{http_code} %{size_download}B\n" http://127.0.0.1:18080/
echo "  标题命中: $(grep -c 局域网快传 /tmp/idx.html)"
echo "--- 2) 上传 5MB ---"
head -c 5242880 /dev/urandom > /tmp/blob.bin
curl -s -X PUT --data-binary @/tmp/blob.bin "http://127.0.0.1:18080/upload?name=test.apk"; echo ""
echo "--- 3) 列表 ---"
curl -s http://127.0.0.1:18080/api/files; echo ""
echo "--- 4) 下载回比 ---"
curl -s "http://127.0.0.1:18080/d?name=test.apk" -o /tmp/back.bin
echo "  原 $(md5sum /tmp/blob.bin | cut -d' ' -f1)"
echo "  回 $(md5sum /tmp/back.bin | cut -d' ' -f1)"
echo "--- 5) 安装请求 ---"
curl -s -X POST "http://127.0.0.1:18080/install?name=test.apk"; echo ""
grep INSTALL_REQUEST /tmp/server.log | sed 's/^/  /' || echo "  （无回调）"
echo "--- 6) 路径穿越 ---"
curl -s -X PUT --data-binary "evil" "http://127.0.0.1:18080/upload?name=../../evil.txt"; echo ""
echo "  /tmp/recv: $(ls /tmp/recv | tr '\n' ' ')"
echo "  /tmp/evil.txt: $(ls /tmp/evil.txt 2>/dev/null || echo 不存在)"
echo "--- 7) 重名 ---"
curl -s -X PUT --data-binary "second" "http://127.0.0.1:18080/upload?name=test.apk"; echo ""
echo "  目录: $(ls /tmp/recv | tr '\n' ' ')"
echo "--- 8) 删除 ---"
curl -s -X POST "http://127.0.0.1:18080/delete?name=test%281%29.apk"; echo ""
echo "  剩余: $(ls /tmp/recv | tr '\n' ' ')"

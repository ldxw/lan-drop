#!/bin/sh
if [ ! -d /opt/kotlinc ]; then
  mkdir -p /opt && cd /opt && jar xf /work/kotlin.zip && cd /work
  chmod -R +x /opt/kotlinc/bin
fi
/opt/kotlinc/bin/kotlinc src/HttpServer.kt src/Update.kt src/Harness.kt -include-runtime -d /tmp/test.jar 2>&1 | grep -v "^warning:" || true
/opt/kotlinc/bin/kotlinc tools/jvmtest/UpdateTest.kt src/Update.kt -include-runtime -d /tmp/update.jar 2>&1 | grep -v "^warning:" || true
echo "--- 0) Update 单元 ---"
java -jar /tmp/update.jar | sed 's/^/  /'
rm -rf /tmp/recv; mkdir -p /tmp/recv
java -jar /tmp/test.jar /tmp/recv /work/page.html 18080 > /tmp/server.log 2>&1 &
for i in $(seq 1 40); do grep -q STARTED /tmp/server.log && break; sleep 1; done

echo "--- 1) 首页 ---"
curl -s -o /tmp/idx.html -w "  HTTP %{http_code} %{size_download}B\n" http://127.0.0.1:18080/
echo "  标题: $(grep -c 局域网快传 /tmp/idx.html) | 文件夹按钮: $(grep -c 选文件夹 /tmp/idx.html) | 续传逻辑: $(grep -c api/stat /tmp/idx.html)"

echo "--- 2) stat（不存在）---"
curl -s "http://127.0.0.1:18080/api/stat?name=big.apk"; echo ""

echo "--- 3) 断点续传：先传前 2MB ---"
head -c 5242880 /dev/urandom > /tmp/blob.bin
head -c 2097152 /tmp/blob.bin > /tmp/part1.bin
curl -s -X PUT --data-binary @/tmp/part1.bin "http://127.0.0.1:18080/upload?name=big.apk&offset=0"; echo ""
curl -s "http://127.0.0.1:18080/api/stat?name=big.apk"; echo ""

echo "--- 4) 从 2MB 处续传剩余 3MB ---"
tail -c +2097153 /tmp/blob.bin > /tmp/part2.bin
curl -s -X PUT --data-binary @/tmp/part2.bin "http://127.0.0.1:18080/upload?name=big.apk&offset=2097152"; echo ""
echo "  原 $(md5sum /tmp/blob.bin | cut -d' ' -f1)"
echo "  回 $(md5sum /tmp/recv/big.apk | cut -d' ' -f1)"

echo "--- 5) 续传偏移越界（文件只有 5MB，声称从 9MB 续）---"
curl -s -X PUT --data-binary "x" "http://127.0.0.1:18080/upload?name=big.apk&offset=9437184"; echo ""

echo "--- 6) 整个文件夹（相对路径）---"
curl -s -X PUT --data-binary "ep1" "http://127.0.0.1:18080/upload?name=%E5%89%A7%E9%9B%86/%E7%AC%AC1%E5%AD%A3/01.mp4&offset=0"; echo ""
curl -s -X PUT --data-binary "ep2" "http://127.0.0.1:18080/upload?name=%E5%89%A7%E9%9B%86/%E7%AC%AC1%E5%AD%A3/02.mp4&offset=0"; echo ""
echo "  磁盘: $(find /tmp/recv -type f | sed 's|/tmp/recv/||' | tr '\n' ' ')"

echo "--- 7) 列表（应带相对路径）---"
curl -s http://127.0.0.1:18080/api/files; echo ""

echo "--- 8) 穿越 ---"
curl -s -X PUT --data-binary "evil" "http://127.0.0.1:18080/upload?name=../../evil.txt&offset=0"; echo ""
echo "  /tmp/recv 下: $(find /tmp/recv -maxdepth 1 -type f | tr '\n' ' ') | /tmp/evil.txt: $(ls /tmp/evil.txt 2>/dev/null || echo 不存在)"

echo "--- 9) 删除子目录文件后清空目录 ---"
curl -s -X POST "http://127.0.0.1:18080/delete?name=%E5%89%A7%E9%9B%86/%E7%AC%AC1%E5%AD%A3/01.mp4"; echo ""
curl -s -X POST "http://127.0.0.1:18080/delete?name=%E5%89%A7%E9%9B%86/%E7%AC%AC1%E5%AD%A3/02.mp4"; echo ""
echo "  剩余目录: $(find /tmp/recv -type d | sed 's|/tmp/recv||' | tr '\n' ' ')"

echo "--- 10) 设置读写 ---"
curl -s "http://127.0.0.1:18080/api/settings?proxy=https%3A%2F%2Fghfast.top%2F&autoCheck=0" -X POST; echo ""
curl -s http://127.0.0.1:18080/api/settings; echo ""

echo "--- 11) 安装请求 ---"
curl -s -X POST "http://127.0.0.1:18080/install?name=big.apk"; echo ""
grep INSTALL_REQUEST /tmp/server.log | sed 's/^/  /'

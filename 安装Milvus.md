使用 docker compose 进行安装

```
  services:
    etcd:
      container_name: milvus-etcd
      hostname: milvus-etcd
      image: quay.io/coreos/etcd:v3.5.0
      environment:
        - ETCD_AUTO_COMPACTION_MODE=revision
        - ETCD_AUTO_COMPACTION_RETENTION=1000
        - ETCD_QUOTA_BACKEND_BYTES=4294967296
      volumes:
        - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/etcd:/etcd
      command: >
        etcd
        -advertise-client-urls=http://0.0.0.0:2379
        -listen-client-urls=http://0.0.0.0:2379
        --data-dir=/etcd

    minio:
      container_name: milvus-minio
      hostname: milvus-minio
      image: minio/minio:RELEASE.2023-03-20T20-16-18Z
      environment:
        MINIO_ACCESS_KEY: minioadmin
        MINIO_SECRET_KEY: minioadmin
      volumes:
        - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/minio:/minio_data
      command: minio server /minio_data --console-address ":9001"
      healthcheck:
        test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
        interval: 30s
        timeout: 20s
        retries: 3

    standalone:
      container_name: milvus-standalone
      hostname: milvus-standalone
      image: milvusdb/milvus:v2.4.0
      command: ["milvus", "run", "standalone"]
      environment:
        ETCD_ENDPOINTS: milvus-etcd:2379
        MINIO_ADDRESS: milvus-minio:9000
      volumes:
        - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/milvus:/var/lib/milvus
      ports:
        - "19530:19530"
        - "9091:9091"
      depends_on:
        - etcd
        - minio

  networks:
    default:
      name: milvu
```



docker compose up -d  开始进行构建



 docker ps  查看运行状态

 CONTAINER ID   IMAGE                                      COMMAND                  CREATED        STATUS                             PORTS                                                                                          NAMES ae9b8519f1ed   milvusdb/milvus:v2.4.0                     "/tini -- milvus run…"   19 hours ago   Up 11 seconds                      0.0.0.0:9091->9091/tcp, [::]:9091->9091/tcp, 0.0.0.0:19530->19530/tcp, [::]:19530->19530/tcp   milvus-standalone 006d0f06ae21   quay.io/coreos/etcd:v3.5.0                 "etcd -advertise-cli…"   19 hours ago   Up 12 seconds                      2379-2380/tcp                                                                                  milvus-etcd a7671c0c4271   minio/minio:RELEASE.2023-03-20T20-16-18Z   "/usr/bin/docker-ent…"   19 hours ago   Up 11 seconds (health: starting)   9000/tcp                                                                                       milvus-minio
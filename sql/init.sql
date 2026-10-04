create database netdisk;
use netdisk;

create table email_code
(
    email       varchar(150) not null comment '邮箱',
    code        varchar(5)   not null comment '验证码',
    create_time datetime     null comment '创建时间',
    status      tinyint(1)   null comment '0:未使用 1:已使用',
    purpose     tinyint     not null default 0 comment '0:注册 1:找回密码',
    primary key (email, code, purpose)
)
    comment '邮箱验证码';

create table file_info
(
    file_id          varchar(10)          not null comment '文件ID',
    user_id          varchar(10)          not null comment '用户ID',
    file_md5         varchar(32)          null comment 'md5值，第一次上传记录',
    file_pid         varchar(10)          null comment '父级ID',
    file_size        bigint               null comment '文件大小字节',
    file_name        varchar(200)         null comment '文件名称',
    file_cover       varchar(100)         null comment '封面',
    file_path        varchar(100)         null comment '文件路径',
    create_time      datetime             null comment '创建时间',
    last_update_time datetime             null comment '最后更新时间',
    folder_type      tinyint(1)           null comment '0:文件 1:目录',
    file_category    tinyint(1)           null comment '1:视频 2:音频  3:图片 4:文档 5:其他',
    file_type        tinyint(1)           null comment ' 1:视频 2:音频  3:图片 4:pdf 5:doc 6:excel 7:txt 8:code 9:zip 10:其他',
    status           tinyint(1)           null comment '0:转码中 1转码失败 2:转码成功',
    recovery_time    datetime             null comment '回收站时间',
    del_flag         tinyint(1) default 2 null comment '删除标记 0:删除  1:回收站  2:正常 3: 彻底删除',
    primary key (file_id, user_id)
)
    comment '文件信息';

create index idx_create_time
    on file_info (create_time);

create index idx_del_flag
    on file_info (del_flag);

create index idx_file_pid
    on file_info (file_pid);

create index idx_md5
    on file_info (file_md5);

create index idx_recovery_time
    on file_info (recovery_time);

create index idx_user_id
    on file_info (user_id);

create table file_share
(
    share_id    varchar(20)   not null comment '分享ID'
        primary key,
    file_id     varchar(10)   not null comment '文件ID',
    user_id     varchar(10)   not null comment '用户ID',
    valid_type  tinyint(1)    null comment '有效期类型 0:1天 1:7天 2:30天 3:永久有效',
    expire_time datetime      null comment '失效时间',
    share_time  datetime      null comment '分享时间',
    code        varchar(5)    null comment '提取码',
    show_count  int default 0 null comment '浏览次数'
)
    comment '分享信息';

create index idx_file_id
    on file_share (file_id);

create index idx_share_time
    on file_share (share_time);

create index idx_user_id
    on file_share (user_id);

create table user_info
(
    user_id         varchar(15)  not null comment '用户ID'
        primary key,
    nick_name       varchar(20)  null comment '昵称',
    email           varchar(150) null comment '邮箱',
    qq_open_id      varchar(35)  null comment 'QQ_OPenId',
    qq_avatar       varchar(150) null comment 'QQ头像',
    password        varchar(255) null comment '带盐密码哈希',
    session_version bigint       not null default 0 comment '会话撤销版本',
    create_time     timestamp    null comment '创建时间',
    last_login_time timestamp    null comment '最后登录时间',
    status          tinyint      null comment '用户状态: 0:禁用 1:启用',
    use_space       bigint       null comment '使用空间: byte',
    total_space     bigint       null comment '总空间:byte',
    constraint key_email
        unique (email),
    constraint key_nick_name
        unique (nick_name),
    constraint key_qq_open_id
        unique (qq_open_id)
)
    comment '用户信息';

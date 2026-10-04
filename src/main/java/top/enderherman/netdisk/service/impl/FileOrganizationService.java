package top.enderherman.netdisk.service.impl;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.enderherman.netdisk.common.constants.Constants;
import top.enderherman.netdisk.common.exceptions.BusinessException;
import top.enderherman.netdisk.common.utils.FileNames;
import top.enderherman.netdisk.common.utils.StringUtils;
import top.enderherman.netdisk.entity.dto.FileListRequest;
import top.enderherman.netdisk.entity.enums.*;
import top.enderherman.netdisk.entity.pojo.FileInfo;
import top.enderherman.netdisk.entity.query.FileQuery;
import top.enderherman.netdisk.entity.query.SimplePage;
import top.enderherman.netdisk.entity.vo.PaginationResultVO;
import top.enderherman.netdisk.mapper.FileMapper;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileOrganizationService {
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "fileName", "file_name", "fileSize", "file_size",
            "createTime", "create_time", "lastUpdateTime", "last_update_time");
    @Resource private FileMapper<FileInfo, FileQuery> fileMapper;

    @Transactional(rollbackFor = Exception.class)
    public FileInfo newFolder(String userId, String parentId, String name) {
        name = FileNames.requireValid(name);
        Map<String, FileInfo> files = lockedFiles(userId);
        requireFolder(parentId, files);
        rejectDuplicate(files, parentId, name, null);
        Date now = new Date();
        FileInfo folder = new FileInfo();
        do { folder.setFileId(StringUtils.getRandomString(Constants.LENGTH_10)); }
        while (files.containsKey(folder.getFileId()));
        folder.setUserId(userId);
        folder.setFilePid(parentId);
        folder.setFileName(name);
        folder.setFolderType(FileFolderTypeEnum.FOLDER.getType());
        folder.setStatus(FileStatusEnum.USING.getStatus());
        folder.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        folder.setCreateTime(now);
        folder.setLastUpdateTime(now);
        fileMapper.insert(folder);
        return folder;
    }

    /** fileName 为完整文件名；只修改展示/下载名称，不转换内容或重建媒体派生物。 */
    @Transactional(rollbackFor = Exception.class)
    public FileInfo rename(String userId, String fileId, String fileName) {
        String name = FileNames.requireValid(fileName);
        Map<String, FileInfo> files = lockedFiles(userId);
        FileInfo file = requireLive(fileId, files);
        requireFolder(file.getFilePid(), files);
        requireFinished(file);
        rejectDuplicate(files, file.getFilePid(), name, fileId);
        if (name.equals(file.getFileName())) return file;
        FileInfo update = new FileInfo();
        update.setFileName(name);
        update.setLastUpdateTime(new Date());
        fileMapper.updateByFileIdAndUserId(update, fileId, userId);
        file.setFileName(name);
        file.setLastUpdateTime(update.getLastUpdateTime());
        return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public void move(String userId, String fileIds, String targetId) {
        Map<String, FileInfo> files = lockedFiles(userId);
        requireFolder(targetId, files);
        Set<String> ids = parseIds(fileIds, false);
        for (String id : ids) {
            FileInfo file = requireLive(id, files);
            requireFolder(file.getFilePid(), files);
            requireFinished(file);
        }
        Set<String> targetPath = ancestorIds(targetId, files);
        if (ids.stream().anyMatch(targetPath::contains)) {
            throw new BusinessException("不能将目录移动到自身或其子目录");
        }
        List<FileInfo> roots = ids.stream().map(files::get)
                .filter(file -> Collections.disjoint(ids, ancestorIds(file.getFilePid(), files))).toList();
        Set<String> names = occupiedNames(files, targetId, null);
        // 所有选择和目标验证完毕后才写数据库，整个批次共用事务和用户锁。
        for (FileInfo root : roots) {
            if (targetId.equals(root.getFilePid())) continue;
            String name = FileNames.unique(root.getFileName(), isFolder(root), names);
            names.add(FileNames.key(name));
            FileInfo update = new FileInfo();
            update.setFilePid(targetId);
            update.setFileName(name);
            update.setLastUpdateTime(new Date());
            fileMapper.updateByFileIdAndUserId(update, root.getFileId(), userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<FileInfo> folders(String userId, String parentId, String currentFileIds) {
        Map<String, FileInfo> files = lockedFiles(userId);
        requireFolder(parentId, files);
        Set<String> excluded = parseIds(currentFileIds, true);
        for (String id : excluded) requireLive(id, files);
        if (!Collections.disjoint(excluded, ancestorIds(parentId, files))) return List.of();
        return files.values().stream().filter(file -> parentId.equals(file.getFilePid())
                        && isLive(file) && isFolder(file) && !excluded.contains(file.getFileId()))
                .sorted(Comparator.comparing((FileInfo file) -> FileNames.key(file.getFileName()))
                        .thenComparing(FileInfo::getFileId)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public PaginationResultVO<FileInfo> list(String userId, FileListRequest request, String category) {
        lockUser(userId);
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        query.setDelFlag(FileDeleteFlagEnum.USING.getFlag());
        String term = request.getFileNameFuzzy() == null ? "" : request.getFileNameFuzzy().strip();
        if (term.length() > 200 || term.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        FileCategoryEnum fileCategory = null;
        if (!StringUtils.isEmpty(category) && !"all".equals(category)) {
            fileCategory = FileCategoryEnum.getByCode(category);
            if (fileCategory == null) throw new BusinessException(ResponseCodeEnum.CODE_600);
            query.setFileCategory(fileCategory.getCategory());
        }
        String parent = request.getFilePid();
        // 未指定目录时，分类和搜索查询全部正常文件；普通浏览默认根目录。
        if (StringUtils.isEmpty(parent) && term.isEmpty() && fileCategory == null) parent = Constants.ZERO_STR;
        if (!StringUtils.isEmpty(parent)) {
            validateFolderPath(userId, parent);
            query.setFilePid(parent);
        }
        if (!term.isEmpty()) {
            query.setFileNameSearch(term.replace("!", "!!").replace("%", "!%").replace("_", "!_"));
        }
        query.setFolderType(bounded(request.getFolderType(), 0, 1));
        query.setFileType(bounded(request.getFileType(), 1, 10));
        query.setStatus(bounded(request.getStatus(), 0, 2));
        String field = StringUtils.isEmpty(request.getSortField()) ? "lastUpdateTime" : request.getSortField();
        String direction = StringUtils.isEmpty(request.getSortDirection()) ? "desc" : request.getSortDirection();
        if (!SORT_COLUMNS.containsKey(field) || (!"asc".equals(direction) && !"desc".equals(direction))) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        query.setOrderBy("folder_type desc, " + SORT_COLUMNS.get(field) + " " + direction + ", file_id asc");
        int pageNo = request.getPageNo() == null ? 1 : request.getPageNo();
        int pageSize = request.getPageSize() == null ? 20 : request.getPageSize();
        if (pageNo < 1 || pageSize < 1) throw new BusinessException(ResponseCodeEnum.CODE_600);
        pageSize = Math.min(pageSize, 100);
        int count = fileMapper.selectCount(query);
        SimplePage page = new SimplePage(pageNo, count, pageSize);
        query.setSimplePage(page);
        return new PaginationResultVO<>(count, page.getPageSize(), page.getPageNo(),
                page.getPageTotal(), fileMapper.selectList(query));
    }

    private Integer bounded(Integer value, int minimum, int maximum) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        return value;
    }

    private void validateFolderPath(String userId, String folderId) {
        Set<String> visited = new HashSet<>();
        while (!Constants.ZERO_STR.equals(folderId)) {
            validId(folderId);
            if (!visited.add(folderId)) throw new BusinessException("目录结构异常");
            FileInfo folder = fileMapper.selectByFileIdAndUserId(folderId, userId);
            if (folder == null || !isFolder(folder) || !isLive(folder)) {
                throw new BusinessException("目录不存在或已被删除");
            }
            folderId = folder.getFilePid();
        }
    }

    private Map<String, FileInfo> lockedFiles(String userId) {
        lockUser(userId);
        FileQuery query = new FileQuery();
        query.setUserId(userId);
        return fileMapper.selectList(query).stream().collect(Collectors.toMap(
                FileInfo::getFileId, file -> file, (a, b) -> a, LinkedHashMap::new));
    }

    private void lockUser(String userId) {
        if (StringUtils.isEmpty(userId) || fileMapper.lockUserForStorage(userId) == null) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
    }

    private Set<String> parseIds(String value, boolean optional) {
        if (StringUtils.isEmpty(value)) {
            if (optional) return Set.of();
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        String[] parts = value.split(",", -1);
        if (parts.length > 500) throw new BusinessException("每次最多选择 500 项");
        Set<String> result = new LinkedHashSet<>();
        for (String part : parts) { validId(part); result.add(part); }
        return result;
    }

    private void validId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9]{1,10}")) throw new BusinessException(ResponseCodeEnum.CODE_600);
    }

    private FileInfo requireLive(String id, Map<String, FileInfo> files) {
        validId(id);
        FileInfo file = files.get(id);
        if (file == null || !isLive(file)) throw new BusinessException("文件不存在或已被删除");
        return file;
    }

    private void requireFinished(FileInfo file) {
        if (FileStatusEnum.TRANSFER.getStatus().equals(file.getStatus())) {
            throw new BusinessException("文件正在处理，请稍后再试");
        }
    }

    private void requireFolder(String id, Map<String, FileInfo> files) { ancestorIds(id, files); }

    private Set<String> ancestorIds(String id, Map<String, FileInfo> files) {
        Set<String> path = new HashSet<>();
        while (!Constants.ZERO_STR.equals(id)) {
            FileInfo file = requireLive(id, files);
            if (!isFolder(file)) throw new BusinessException("目标必须是文件夹");
            if (!path.add(id)) throw new BusinessException("目录结构异常");
            id = file.getFilePid();
        }
        return path;
    }

    private boolean isLive(FileInfo file) { return FileDeleteFlagEnum.USING.getFlag().equals(file.getDelFlag()); }
    private boolean isFolder(FileInfo file) { return FileFolderTypeEnum.FOLDER.getType().equals(file.getFolderType()); }

    private Set<String> occupiedNames(Map<String, FileInfo> files, String parent, String excludedId) {
        return files.values().stream().filter(file -> isLive(file) && parent.equals(file.getFilePid())
                        && !file.getFileId().equals(excludedId))
                .map(file -> FileNames.key(file.getFileName())).collect(Collectors.toSet());
    }

    private void rejectDuplicate(Map<String, FileInfo> files, String parent, String name, String excludedId) {
        if (occupiedNames(files, parent, excludedId).contains(FileNames.key(name))) {
            throw new BusinessException("此目录下已经存在同名文件或文件夹");
        }
    }
}

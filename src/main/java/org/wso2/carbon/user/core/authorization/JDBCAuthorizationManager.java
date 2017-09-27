/*
 * Copyright 2005-2007 WSO2, Inc. (http://wso2.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.user.core.authorization;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.AuthorizationManager;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.internal.UMListenerServiceComponent;
import org.wso2.carbon.user.core.ldap.LDAPConstants;
import org.wso2.carbon.user.core.listener.AuthorizationManagerListener;
import org.wso2.carbon.user.core.profile.ProfileConfigurationManager;
import org.wso2.carbon.user.core.util.DatabaseUtil;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

public class JDBCAuthorizationManager implements AuthorizationManager {

    public static final String CASCADE_DELETE_ENABLED = "isCascadeDeleteEnabled";
    /**
     * The root node of the tree
     */
    private static Log log = LogFactory.getLog(JDBCAuthorizationManager.class);
    private static boolean debug = log.isDebugEnabled();
    private final String GET_ALL_ROLES_OF_USER_ENABLED = "GetAllRolesOfUserEnabled";
    private DataSource dataSource = null;
    private PermissionTree permissionTree = null;
    private AuthorizationCache authorizationCache = null;
    private UserRealm userRealm = null;
    private RealmConfiguration realmConfig = null;
    private boolean caseInSensitiveAuthorizationRules;
    private boolean verifyByRetrievingAllUserRoles;
    private String cacheIdentifier;
    private int tenantId;
    private String isCascadeDeleteEnabled;
    private final String IS_EXISTING_ROLE_PERMISSION_MAPPING =
            "SELECT UM_ID, UM_IS_ALLOWED FROM UM_ROLE_PERMISSION WHERE UM_ROLE_NAME=? " +
            "AND UM_PERMISSION_ID = (SELECT UM_ID FROM UM_PERMISSION WHERE UM_RESOURCE_ID = ? AND UM_ACTION = ? AND " +
            "UM_TENANT_ID=?) AND UM_TENANT_ID=? AND UM_DOMAIN_ID=(SELECT UM_DOMAIN_ID FROM UM_DOMAIN WHERE " +
            "UM_TENANT_ID=? AND UM_DOMAIN_NAME=?)";
    private static final String DELETE_ROLE_PERMISSIONS = "DeleteRolePermissions";
    private static final String DELETE_USER_PERMISSIONS = "DeleteUserPermissions";
    private static final ThreadLocal<Boolean> isSecureCall = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    public JDBCAuthorizationManager(RealmConfiguration realmConfig, Map<String, Object> properties,
                                    ClaimManager claimManager, ProfileConfigurationManager profileManager, UserRealm realm,
                                    Integer tenantId) throws UserStoreException {

        authorizationCache = AuthorizationCache.getInstance();
        if (!"true".equals(realmConfig.getAuthorizationManagerProperty(UserCoreConstants.
                RealmConfig.PROPERTY_AUTHORIZATION_CACHE_ENABLED))) {
            authorizationCache.disableCache();
        }

        if (!"true".equals(realmConfig.getAuthorizationManagerProperty(UserCoreConstants.
                RealmConfig.PROPERTY_CASE_SENSITIVITY))) {
            caseInSensitiveAuthorizationRules = true;
        }

        if ("true".equals(realmConfig.getAuthorizationManagerProperty(GET_ALL_ROLES_OF_USER_ENABLED))) {
            verifyByRetrievingAllUserRoles = true;
        }

        if (!realmConfig.getAuthzProperties().containsKey(DELETE_ROLE_PERMISSIONS)) {
            realmConfig.getAuthzProperties().put(DELETE_ROLE_PERMISSIONS, DBConstants
                    .ON_DELETE_PERMISSION_UM_ROLE_PERMISSIONS_SQL);
        }

        if (!realmConfig.getAuthzProperties().containsKey(DELETE_USER_PERMISSIONS)) {
            realmConfig.getAuthzProperties().put(DELETE_USER_PERMISSIONS, DBConstants
                    .ON_DELETE_PERMISSION_UM_USER_PERMISSIONS_SQL);
        }

        String userCoreCacheIdentifier = realmConfig.getUserStoreProperty(UserCoreConstants.
                RealmConfig.PROPERTY_USER_CORE_CACHE_IDENTIFIER);

        if (userCoreCacheIdentifier != null && userCoreCacheIdentifier.trim().length() > 0) {
            cacheIdentifier = userCoreCacheIdentifier;
        } else {
            cacheIdentifier = UserCoreConstants.DEFAULT_CACHE_IDENTIFIER;
        }

        dataSource = (DataSource) properties.get(UserCoreConstants.DATA_SOURCE);
        if (dataSource == null) {
            dataSource = DatabaseUtil.getRealmDataSource(realmConfig);
            properties.put(UserCoreConstants.DATA_SOURCE, dataSource);
        }

        this.isCascadeDeleteEnabled = realmConfig.getRealmProperty(CASCADE_DELETE_ENABLED);

        this.permissionTree = new PermissionTree(cacheIdentifier, tenantId, dataSource);
        this.realmConfig = realmConfig;
        this.userRealm = realm;
        this.tenantId = tenantId;
        if (log.isDebugEnabled()) {
            log.debug("The jdbcDataSource being used by JDBCAuthorizationManager :: "
                    + dataSource.hashCode());
        }
        this.populatePermissionTreeFromDB();
        this.addInitialData();
    }

    public boolean isRoleAuthorized(String roleName, String resourceId, String action) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            Object object = callSecure("isRoleAuthorized", new Object[]{roleName, resourceId, action}, argTypes);
            return (Boolean) object;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.isRoleAuthorized(roleName, resourceId, action, this)) {
                return false;
            }
        }

        roleName = modify(roleName);
        resourceId = modify(resourceId);
        action = modify(action);

        permissionTree.updatePermissionTree();
        SearchResult sr = permissionTree.getRolePermission(roleName, PermissionTreeUtil
                .actionToPermission(action), null, null, PermissionTreeUtil
                .toComponenets(resourceId));


        if (log.isDebugEnabled()) {
            if (!sr.getLastNodeAllowedAccess()) {
                log.debug(roleName + " role is not Authorized to perform " + action + " on " + resourceId);
            }
        }

        return sr.getLastNodeAllowedAccess();
    }

    public boolean isUserAuthorized(String userName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            Object object = callSecure("isUserAuthorized", new Object[]{userName, resourceId, action}, argTypes);
            return (Boolean) object;
        }

        if (CarbonConstants.REGISTRY_SYSTEM_USERNAME.equals(userName)) {
            return true;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.isUserAuthorized(userName, resourceId, action, this)) {
                return false;
            }
        }

        String unModifiedUser = userName;

        userName = modify(userName);
        resourceId = modify(resourceId);
        action = modify(action);

        try {
            Boolean userAllowed = authorizationCache.isUserAuthorized(cacheIdentifier,
                    tenantId, userName, resourceId, action);
            if (log.isDebugEnabled()) {
                if (userAllowed != null && !userAllowed) {
                    log.debug("Authorization cache hit. " +
                            userName + " user is not Authorized to perform " + action +
                            " on " + resourceId);
                }
            }

            if (userAllowed != null) {
                return userAllowed;
            }

        } catch (AuthorizationCacheException e) {
            // Entry not found in the cache. Just continue.
        }

        if (log.isDebugEnabled()) {
            log.debug("Authorization cache miss for username : " + userName + " resource " + resourceId
                    + " action : " + action);
        }

        permissionTree.updatePermissionTree();

        //following is related with user permission, and it is not hit in the current flow.
        SearchResult sr =
                permissionTree.getUserPermission(userName,
                        PermissionTreeUtil.actionToPermission(action),
                        null, null,
                        PermissionTreeUtil.toComponenets(resourceId));
        if (sr.getLastNodeAllowedAccess()) {
            authorizationCache.addToCache(cacheIdentifier, tenantId, userName, resourceId, action, true);
            return true;
        }


        boolean userAllowed = false;
        String[] allowedRoles = modify(getAllowedRolesForResource(resourceId, action));


        if (allowedRoles != null && allowedRoles.length > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Roles which have permission for resource : " + resourceId + " action : " + action);
                for (String allowedRole : allowedRoles) {
                    log.debug("Role :  " + allowedRole);
                }
            }

            if (verifyByRetrievingAllUserRoles) {
                String[] roles = null;
                try {
                    roles = userRealm.getUserStoreManager().getRoleListOfUser(userName);
                } catch (UserStoreException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error getting role list of user : " + userName, e);
                    }
                }

                if (roles == null || roles.length == 0) {
                    AbstractUserStoreManager manager = (AbstractUserStoreManager) userRealm.getUserStoreManager();
                    roles = manager.doGetRoleListOfUser(userName, "*");
                }

                Set<String> allowedRoleSet = new HashSet<String>(Arrays.asList(allowedRoles));
                Set<String> userRoleSet = new HashSet<String>(Arrays.asList(modify(roles)));
                allowedRoleSet.retainAll(userRoleSet);

                if (log.isDebugEnabled()) {
                    for (String allowedRole : allowedRoleSet) {
                        log.debug(userName + " user has permitted role :  " + allowedRole);
                    }
                }

                if (!allowedRoleSet.isEmpty()) {
                    userAllowed = true;
                }

            } else {
                AbstractUserStoreManager manager = (AbstractUserStoreManager) userRealm.getUserStoreManager();
                for (String role : allowedRoles) {
                    try {
                        if (manager.isUserInRole(unModifiedUser, role)) {
                            if (log.isDebugEnabled()) {
                                log.debug(unModifiedUser + " user is in role :  " + role);
                            }
                            userAllowed = true;
                            break;
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug(unModifiedUser + " user is not in role :  " + role);
                            }
                        }
                    } catch (UserStoreException e) {
                        if (log.isDebugEnabled()) {
                            log.debug(unModifiedUser + " user is not in role :  " + role, e);
                        }
                    }
                }
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("No roles have permission for resource : " + resourceId + " action : " + action);
            }
        }

        //need to add the authorization decision taken by role based permission
        authorizationCache.addToCache(cacheIdentifier, this.tenantId, userName, resourceId, action,
                userAllowed);

        if (log.isDebugEnabled()) {
            if (!userAllowed) {
                log.debug(userName + " user is not Authorized to perform " + action + " on " + resourceId);
            }
        }

        return userAllowed;
    }

    public String[] getAllowedRolesForResource(String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getAllowedRolesForResource", new Object[]{resourceId, action}, argTypes);
            return (String[]) object;
        }

        resourceId = modify(resourceId);
        action = modify(action);
        TreeNode.Permission permission = PermissionTreeUtil.actionToPermission(action);
        permissionTree.updatePermissionTree();
        SearchResult sr =
                permissionTree.getAllowedRolesForResource(null,
                        null,
                        permission,
                        PermissionTreeUtil.toComponenets(resourceId));

        if (debug) {
            log.debug("Allowed roles for the ResourceID: " + resourceId + " Action: " + action);
            String[] roles = sr.getAllowedEntities().toArray(new String[sr.getAllowedEntities().size()]);
            for (String role : roles) {
                log.debug("role: " + role);
            }
        }

        return sr.getAllowedEntities().toArray(new String[sr.getAllowedEntities().size()]);
    }

    public String[] getExplicitlyAllowedUsersForResource(String resourceId, String action)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getExplicitlyAllowedUsersForResource", new Object[]{resourceId, action},
                    argTypes);
            return (String[]) object;
        }
        resourceId = modify(resourceId);
        action = modify(action);
        TreeNode.Permission permission = PermissionTreeUtil.actionToPermission(action);
        permissionTree.updatePermissionTree();
        SearchResult sr =
                permissionTree.getAllowedUsersForResource(null,
                        null,
                        permission,
                        PermissionTreeUtil.toComponenets(resourceId));

        if (debug) {
            log.debug("Explicityly allowed roles for the ResourceID: " + resourceId + " Action: " + action);
            String[] roles = sr.getAllowedEntities().toArray(new String[sr.getAllowedEntities().size()]);
            for (String role : roles) {
                log.debug("role: " + role);
            }
        }

        return sr.getAllowedEntities().toArray(new String[sr.getAllowedEntities().size()]);
    }

    public String[] getDeniedRolesForResource(String resourceId, String action)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getDeniedRolesForResource", new Object[]{resourceId, action}, argTypes);
            return (String[]) object;
        }
        resourceId = modify(resourceId);
        action = modify(action);
        TreeNode.Permission permission = PermissionTreeUtil.actionToPermission(action);
        permissionTree.updatePermissionTree();
        SearchResult sr =
                permissionTree.getDeniedRolesForResource(null,
                        null,
                        permission,
                        PermissionTreeUtil.toComponenets(resourceId));
        return sr.getDeniedEntities().toArray(new String[sr.getAllowedEntities().size()]);
    }

    public String[] getExplicitlyDeniedUsersForResource(String resourceId, String action)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getExplicitlyDeniedUsersForResource", new Object[]{resourceId, action},
                    argTypes);
            return (String[]) object;
        }
        resourceId = modify(resourceId);
        action = modify(action);
        TreeNode.Permission permission = PermissionTreeUtil.actionToPermission(action);
        permissionTree.updatePermissionTree();
        SearchResult sr =
                permissionTree.getDeniedUsersForResource(null,
                        null,
                        permission,
                        PermissionTreeUtil.toComponenets(resourceId));
        return sr.getDeniedEntities().toArray(new String[sr.getAllowedEntities().size()]);
    }

    public String[] getAllowedUIResourcesForUser(String userName, String permissionRootPath)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            Object object = callSecure("getAllowedUIResourcesForUser", new Object[]{userName, permissionRootPath},
                    argTypes);
            return (String[]) object;
        }
        if (verifyByRetrievingAllUserRoles) {

            List<String> lstPermissions = new ArrayList<String>();
            String[] roles = this.userRealm.getUserStoreManager().getRoleListOfUser(userName);
            roles = modify(roles);
            permissionTree.updatePermissionTree();
            permissionTree.getUIResourcesForRoles(roles, lstPermissions, permissionRootPath);
            String[] permissions = lstPermissions.toArray(new String[lstPermissions.size()]);
            return UserCoreUtil.optimizePermissions(permissions);

        } else {

            permissionRootPath = modify(permissionRootPath);
            List<String> lstPermissions = new ArrayList<String>();
            List<String> resourceIds = getUIPermissionId();
            if (resourceIds != null) {
                for (String resourceId : resourceIds) {
                    if (isUserAuthorized(userName, resourceId, CarbonConstants.UI_PERMISSION_ACTION)) {
                        if (permissionRootPath == null) {
                            lstPermissions.add(resourceId);
                        } else {
                            if (resourceId.contains(permissionRootPath)) {
                                lstPermissions.add(resourceId);
                            }
                        }
                    }//authorization check up
                }//loop over resource list
            }//resource ID checkup

            String[] permissions = lstPermissions.toArray(new String[lstPermissions.size()]);
            String[] optimizedList = UserCoreUtil.optimizePermissions(permissions);

            if (debug) {
                log.debug("Allowed UI Resources for User: " + userName + " in permissionRootPath: " +
                        permissionRootPath);
                for (String resource : optimizedList) {
                    log.debug("Resource: " + resource);
                }
            }

            return optimizedList;
        }
    }

    public void authorizeRole(String roleName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("authorizeRole", new Object[]{roleName, resourceId, action}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.authorizeRole(roleName, resourceId, action, this)) {
                return;
            }
        }

        if (resourceId == null || action == null) {
            log.error("Invalid data provided at authorization code");
            throw new UserStoreException("Invalid data provided");
        }
        roleName = modify(roleName);
        resourceId = modify(resourceId);
        action = modify(action);
        addAuthorizationForRole(roleName, resourceId, action, UserCoreConstants.ALLOW, true);
    }

    public void denyRole(String roleName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("denyRole", new Object[]{roleName, resourceId, action}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.denyRole(roleName, resourceId, action, this)) {
                return;
            }
        }

        if (resourceId == null || action == null) {
            log.error("Invalid data provided at authorization code");
            throw new UserStoreException("Invalid data provided");
        }
        roleName = modify(roleName);
        resourceId = modify(resourceId);
        action = modify(action);
        addAuthorizationForRole(roleName, resourceId, action, UserCoreConstants.DENY, true);
    }

    public void authorizeUser(String userName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("authorizeUser", new Object[]{userName, resourceId, action}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.authorizeUser(userName, resourceId, action, this)) {
                return;
            }
        }

        if (resourceId == null || action == null) {
            log.error("Invalid data provided at authorization code");
            throw new UserStoreException("Invalid data provided");
        }
        userName = modify(userName);
        resourceId = modify(resourceId);
        action = modify(action);
        addAuthorizationForUser(userName, resourceId, action, UserCoreConstants.ALLOW, true);
    }

    public void denyUser(String userName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("denyUser", new Object[]{userName, resourceId, action}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.denyUser(userName, resourceId, action, this)) {
                return;
            }
        }

        if (resourceId == null || action == null) {
            log.error("Invalid data provided at authorization code");
            throw new UserStoreException("Invalid data provided");
        }

        userName = modify(userName);
        resourceId = modify(resourceId);
        action = modify(action);

        addAuthorizationForUser(userName, resourceId, action, UserCoreConstants.DENY, true);
    }

    public void clearResourceAuthorizations(String resourceId) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("clearResourceAuthorizations", new Object[]{resourceId}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearResourceAuthorizations(resourceId, this)) {
                return;
            }
        }
        resourceId = modify(resourceId);
        /**
         * Need to clear authz cache when resource authorization is cleared.
         */
        authorizationCache.clearCacheByTenant(this.tenantId);

        Connection dbConnection = null;
        try {
            dbConnection = getDBConnection();
            if(isCascadeDeleteEnabled == null || !Boolean.parseBoolean(isCascadeDeleteEnabled)) {
                DatabaseUtil.updateDatabase(dbConnection, realmConfig.getAuthzProperties().get(DELETE_ROLE_PERMISSIONS),
                        resourceId, tenantId);
                DatabaseUtil.updateDatabase(dbConnection, realmConfig.getAuthzProperties().get(DELETE_USER_PERMISSIONS),
                        resourceId, tenantId);
            }
            DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_PERMISSION_SQL,
                    resourceId, tenantId);
            permissionTree.clearResourceAuthorizations(resourceId);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while clearing resource authorization for resource id : " + resourceId;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection);
        }
    }

    public void clearRoleAuthorization(String roleName, String resourceId, String action)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("clearRoleAuthorization", new Object[]{roleName, resourceId, action}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearRoleAuthorization(roleName, resourceId, action, this)) {
                return;
            }
        }

        roleName = modify(roleName);
        resourceId = modify(resourceId);
        action = modify(action);

        /*need to clear tenant authz cache once role authorization is removed, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);

        Connection dbConnection = null;
        try {
            dbConnection = getDBConnection();
            String domain = UserCoreUtil.extractDomainFromName(roleName);
            if (domain != null) {
                domain = domain.toUpperCase();
            }
            DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_ROLE_PERMISSION_SQL,
                    UserCoreUtil.removeDomainFromName(roleName), resourceId, action, tenantId, tenantId, tenantId, domain);
            permissionTree.clearRoleAuthorization(roleName, resourceId, action);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while clearing role authorization for role : " + roleName + " & resource id : " +
                    resourceId + " & action : " + action;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection);
        }
    }

    public void clearUserAuthorization(String userName, String resourceId, String action)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class};
            callSecure("clearUserAuthorization", new Object[]{userName, resourceId, action}, argTypes);
            return;
        }
        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearUserAuthorization(userName, resourceId, action, this)) {
                return;
            }
        }

        userName = modify(userName);
        resourceId = modify(resourceId);
        action = modify(action);

        this.authorizationCache.clearCacheEntry(cacheIdentifier, tenantId, userName, resourceId,
                action);

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            int permissionId = this.getPermissionId(dbConnection, resourceId, action);
            if (permissionId == -1) {
                this.addPermissionId(dbConnection, resourceId, action);
            }
            DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_USER_PERMISSION_SQL,
                    userName, resourceId, action, tenantId, tenantId);
            permissionTree.clearUserAuthorization(userName, resourceId, action);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while clearing user authorization for user : " + userName + " & resource id : " +
                    resourceId + " & action : " + action;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    public void clearRoleActionOnAllResources(String roleName, String action)
            throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            callSecure("clearRoleActionOnAllResources", new Object[]{roleName, action}, argTypes);
            return;
        }
        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearRoleActionOnAllResources(roleName, action, this)) {
                return;
            }
        }

        roleName = modify(roleName);
        action = modify(action);

        /*need to clear tenant authz cache once role authorization is removed, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            permissionTree.clearRoleAuthorization(roleName, action);
            String domain = UserCoreUtil.extractDomainFromName(roleName);
            if (domain != null) {
                domain = domain.toUpperCase();
            }
            DatabaseUtil.updateDatabase(dbConnection,
                    DBConstants.DELETE_ROLE_PERMISSIONS_BASED_ON_ACTION, UserCoreUtil.removeDomainFromName(roleName),
                    action, tenantId, tenantId, tenantId, domain);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while clearing role action on all resources for role : " + roleName +
                    " & action : " + action;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    public void clearRoleAuthorization(String roleName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("clearRoleAuthorization", new Object[]{roleName}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearRoleAuthorization(roleName, this)) {
                return;
            }
        }

        roleName = modify(roleName);

        /*need to clear tenant authz cache once role authorization is removed, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);
        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            permissionTree.clearRoleAuthorization(roleName);
            String domain = UserCoreUtil.extractDomainFromName(roleName);
            if (domain != null) {
                domain = domain.toUpperCase();
            }
            DatabaseUtil.updateDatabase(dbConnection,
                    DBConstants.ON_DELETE_ROLE_DELETE_PERMISSION_SQL, UserCoreUtil.removeDomainFromName(roleName),
                    tenantId, tenantId, domain);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage = "Error occurred while clearing role authorization for role : " + roleName;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    public void clearUserAuthorization(String userName) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class};
            callSecure("clearUserAuthorization", new Object[]{userName}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.clearUserAuthorization(userName, this)) {
                return;
            }
        }

        userName = modify(userName);

        this.authorizationCache.clearCacheByTenant(tenantId);

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            permissionTree.clearUserAuthorization(userName);
            DatabaseUtil.updateDatabase(dbConnection,
                    DBConstants.ON_DELETE_USER_DELETE_PERMISSION_SQL, userName, tenantId);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage = "Error occurred while clearing user authorization for user : " + userName;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }

    }

    public void resetPermissionOnUpdateRole(String roleName, String newRoleName)
            throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class};
            callSecure("resetPermissionOnUpdateRole", new Object[]{roleName, newRoleName}, argTypes);
            return;
        }

        for (AuthorizationManagerListener listener : UMListenerServiceComponent
                .getAuthorizationManagerListeners()) {
            if (!listener.resetPermissionOnUpdateRole(roleName, newRoleName, this)) {
                return;
            }
        }

        roleName = modify(roleName);
        newRoleName = modify(newRoleName);

        /*need to clear tenant authz cache when role is updated, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);

        String sqlStmt = DBConstants.UPDATE_UM_ROLE_NAME_PERMISSION_SQL;
        if (sqlStmt == null) {
            throw new UserStoreException("The sql statement for update role name is null");
        }
        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            permissionTree.updateRoleNameInCache(roleName, newRoleName);
            String domain = UserCoreUtil.extractDomainFromName(newRoleName);
            newRoleName = UserCoreUtil.removeDomainFromName(newRoleName);
            roleName = UserCoreUtil.removeDomainFromName(roleName);
            if (domain != null) {
                domain = domain.toUpperCase();
            }
            DatabaseUtil.updateDatabase(dbConnection, sqlStmt, newRoleName, roleName, tenantId, tenantId, domain);
            dbConnection.commit();
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred on permission resetting while updating role : " + roleName + " to role : " +
                    newRoleName;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    public void addAuthorization(String subject, String resourceId, String action,
                                 boolean authorized, boolean isRole) throws UserStoreException {

        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class, boolean.class, boolean.class};
            callSecure("addAuthorization", new Object[]{subject, resourceId, action, authorized, isRole}, argTypes);
            return;
        }

        short allow = 0;
        if (authorized) {
            allow = UserCoreConstants.ALLOW;
        }
        if (isRole) {
            addAuthorizationForRole(subject, resourceId, action, allow, false);
        } else {
            addAuthorizationForUser(subject, resourceId, action, allow, false);
        }
    }

    private void addAuthorizationForRole(String roleName, String resourceId, String action,
                                         short allow, boolean updateCache) throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class, short.class, boolean.class};
            callSecure("addAuthorizationForRole", new Object[]{roleName, resourceId, action, allow, updateCache},
                    argTypes);
            return;
        }

        /*need to clear tenant authz cache once role authorization is added, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        short isAllowed = -1;
        boolean isRolePermissionExisting = false;
        try {
            dbConnection = getDBConnection();
            int permissionId = this.getPermissionId(dbConnection, resourceId, action);
            if (permissionId == -1) {
                this.addPermissionId(dbConnection, resourceId, action);
                permissionId = this.getPermissionId(dbConnection, resourceId, action);
            }
            String domain = UserCoreUtil.extractDomainFromName(roleName);
            if (domain != null) {
                domain = domain.toUpperCase();
            }
            //check if system role
            boolean isSystemRole = UserCoreUtil.isSystemRole(roleName, this.tenantId, this.dataSource);

            if (isSystemRole) {
                domain = UserCoreConstants.SYSTEM_DOMAIN_NAME;
            } else if (domain == null) {
                // assume as primary domain
                domain = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
            }
/*

            DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_ROLE_PERMISSION_SQL,
                    UserCoreUtil.removeDomainFromName(roleName), resourceId, action,
                    tenantId, tenantId, tenantId, domain);

*/
            prepStmt = dbConnection.prepareStatement(IS_EXISTING_ROLE_PERMISSION_MAPPING);
            prepStmt.setString(1, UserCoreUtil.removeDomainFromName(roleName));
            prepStmt.setString(2, resourceId);
            prepStmt.setString(3, action);
            prepStmt.setInt(4, tenantId);
            prepStmt.setInt(5, tenantId);
            prepStmt.setInt(6, tenantId);
            prepStmt.setString(7, domain);

            rs = prepStmt.executeQuery();

            if (rs != null && rs.next()) {
                isAllowed = rs.getShort(2);
                isRolePermissionExisting = true;
            } else {
                // Role permission not existing
                isRolePermissionExisting = false;
            }

            if(isRolePermissionExisting && isAllowed != allow){
                DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_ROLE_PERMISSION_SQL,
                       UserCoreUtil.removeDomainFromName(roleName), resourceId, action,
                      tenantId, tenantId, tenantId, domain);
                isRolePermissionExisting = false;
            }

            if(!isRolePermissionExisting) {

                if (log.isDebugEnabled()) {
                    log.debug("Adding permission Id: " + permissionId + " to the role: "
                              + UserCoreUtil.removeDomainFromName(roleName) + " of tenant: " + tenantId
                              + " of domain: " + domain + " to resource: " + resourceId);
                }

                DatabaseUtil.updateDatabase(dbConnection, DBConstants.ADD_ROLE_PERMISSION_SQL,
                                            permissionId, UserCoreUtil.removeDomainFromName(roleName), allow,
                                            tenantId, tenantId, domain);
            }

            if (updateCache) {
                if (allow == UserCoreConstants.ALLOW) {
                    permissionTree.authorizeRoleInTree(roleName, resourceId, action, true);
                } else {
                    permissionTree.denyRoleInTree(roleName, resourceId, action, true);
                }
            }
            dbConnection.commit();
        } catch (Exception e) {
            /*
            The db.commit() throws SQLException
            authorizeRoleInTree method and denyRoleInTree method throws UserStoreException.
            dbConnection should be rolled back when an exception is thrown
            */
            try {
                if (dbConnection != null) {
                    dbConnection.rollback();
                }
            } catch (SQLException e1) {
                String errorMessage =
                        "Error in DB connection rollback for role : " + roleName + " & resource id : " + resourceId +
                        " & action : " + action + " & allow : " + " & update cache : " + updateCache;
                if (log.isDebugEnabled()) {
                    log.debug(errorMessage, e1);
                }
                throw new UserStoreException(errorMessage, e1);
            }
            String errorMessage =
                    "Error occurred while adding authorization for role : " + roleName + " & resource id : " +
                    resourceId + " & action : " + action + " & allow : " + " & update cache : " + updateCache;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            if(rs != null){
                try{
                    rs.close();
                } catch (SQLException e){
                    log.error("Closing result set failed when adding role permission", e);
                }
            }
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    private void addAuthorizationForUser(String userName, String resourceId, String action,
                                         short allow, boolean updateCache) throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[]{String.class, String.class, String.class, short.class, boolean.class};
            callSecure("addAuthorizationForUser", new Object[]{userName, resourceId, action, allow, updateCache},
                    argTypes);
            return;
        }

        /*need to clear tenant authz cache once role authorization is removed, currently there is
        no way to remove cache entry by role.*/
        authorizationCache.clearCacheByTenant(this.tenantId);

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        try {
            dbConnection = getDBConnection();
            int permissionId = this.getPermissionId(dbConnection, resourceId, action);
            if (permissionId == -1) {
                this.addPermissionId(dbConnection, resourceId, action);
                permissionId = this.getPermissionId(dbConnection, resourceId, action);
            }
            DatabaseUtil.updateDatabase(dbConnection, DBConstants.DELETE_USER_PERMISSION_SQL,
                    userName, resourceId, action, tenantId, tenantId);
            DatabaseUtil.updateDatabase(dbConnection, DBConstants.ADD_USER_PERMISSION_SQL,
                    permissionId, userName, allow, tenantId);
            if (updateCache) {
                if (allow == UserCoreConstants.ALLOW) {
                    permissionTree.authorizeUserInTree(userName, resourceId, action, true);
                } else {
                    permissionTree.denyUserInTree(userName, resourceId, action, true);
                    authorizationCache.clearCacheEntry(cacheIdentifier, tenantId, userName, resourceId,
                            action);
                }
            }
            dbConnection.commit();
        } catch (Exception e) {
            /*
            The db.commit() throws SQLException
            authorizeRoleInTree method and denyRoleInTree method throws UserStoreException.
            dbConnection should be rolled back when an exception is thrown
            */
            try {
                if (dbConnection != null) {
                    dbConnection.rollback();
                }
            } catch (SQLException e1) {
                String errorMessage = "Error in connection rollback for user : " + userName;
                if (log.isDebugEnabled()) {
                    log.debug(errorMessage, e);
                }
                throw new UserStoreException(errorMessage, e1);
            }
            String errorMessage = "Error! " + e.getMessage();
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, prepStmt);
        }
    }

    private List<String> getUIPermissionId() throws UserStoreException {

        Connection dbConnection = null;
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        List<String> resourceIds = new ArrayList<String>();
        try {
            dbConnection = getDBConnection();
            prepStmt = dbConnection.prepareStatement(DBConstants.GET_PERMISSION_SQL);
            prepStmt.setString(1, CarbonConstants.UI_PERMISSION_ACTION);
            prepStmt.setInt(2, tenantId);

            rs = prepStmt.executeQuery();
            if (rs != null) {
                while (rs.next()) {
                    resourceIds.add(rs.getString(1));
                }
            }
            return resourceIds;
        } catch (SQLException e) {
            String errorMessage = "Error occurred while getting ui permission id list";
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(dbConnection, rs, prepStmt);
        }
    }


    private int getPermissionId(Connection dbConnection, String resourceId, String action)
            throws UserStoreException {
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        int value = -1;
        try {
            prepStmt = dbConnection.prepareStatement(DBConstants.GET_PERMISSION_ID_SQL);
            prepStmt.setString(1, resourceId);
            prepStmt.setString(2, action);
            prepStmt.setInt(3, tenantId);

            rs = prepStmt.executeQuery();
            if (rs.next()) {
                value = rs.getInt(1);
            }
            return value;
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while getting permission id for resource id : " + resourceId + " & action : " +
                    action;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(null, rs, prepStmt);
        }
    }

    private void addPermissionId(Connection dbConnection, String resourceId, String action)
            throws UserStoreException {
        PreparedStatement prepStmt = null;
        try {
            prepStmt = dbConnection.prepareStatement(DBConstants.ADD_PERMISSION_SQL);
            prepStmt.setString(1, resourceId);
            prepStmt.setString(2, action);
            prepStmt.setInt(3, tenantId);
            int count = prepStmt.executeUpdate();
            if (log.isDebugEnabled()) {
                log.debug("Executed querry is " + DBConstants.ADD_PERMISSION_SQL
                        + " and number of updated rows :: " + count);
            }
        } catch (SQLException e) {
            String errorMessage =
                    "Error occurred while adding permission id for resource id : " + resourceId + " & action : " +
                    action;
            if (log.isDebugEnabled()) {
                log.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            DatabaseUtil.closeAllConnections(null, prepStmt);
        }
    }

    private Connection getDBConnection() throws SQLException {
        Connection dbConnection = dataSource.getConnection();
        dbConnection.setAutoCommit(false);
        return dbConnection;
    }

    public void populatePermissionTreeFromDB() throws UserStoreException {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[0];
            callSecure("populatePermissionTreeFromDB", new Object[0], argTypes);
            return;
        }
        permissionTree.updatePermissionTreeFromDB();
    }

    /**
     * This method will unload all permission data loaded from a database. This method is useful in a lazy loading
     * scenario.
     */
    public void clearPermissionTree() {
        if (!isSecureCall.get()) {
            Class argTypes[] = new Class[0];
            try {
                callSecure("clearPermissionTree", new Object[0], argTypes);
            } catch (UserStoreException e) {
                return;
            }
        }
        this.permissionTree.clear();
        this.authorizationCache.clearCache();
    }

    public int getTenantId() throws UserStoreException {
        return tenantId;
    }

    /**
     * @param name
     * @return
     */
    private String modify(String name) {
        if (caseInSensitiveAuthorizationRules && name != null) {
            return name.toLowerCase();
        }
        return name;
    }

    /**
     * @param names
     * @return
     */
    private String[] modify(String[] names) {
        if (caseInSensitiveAuthorizationRules && names != null) {
            List<String> list = new ArrayList<String>();
            for (String name : names) {
                list.add(name.toLowerCase());
            }
            return list.toArray(new String[list.size()]);
        }
        return names;
    }

    private void addInitialData() throws UserStoreException {
        String mgtPermissions = realmConfig
                .getAuthorizationManagerProperty(UserCoreConstants.RealmConfig.PROPERTY_EVERYONEROLE_AUTHORIZATION);
        if (mgtPermissions != null) {
            String everyoneRole = realmConfig.getEveryOneRoleName();
            String[] resourceIds = mgtPermissions.split(",");
            for (String resourceId : resourceIds) {
                if (!this.isRoleAuthorized(everyoneRole, resourceId,
                        CarbonConstants.UI_PERMISSION_ACTION)) {
                    this.authorizeRole(everyoneRole, resourceId,
                            CarbonConstants.UI_PERMISSION_ACTION);
                }
            }
        }

        mgtPermissions = realmConfig
                .getAuthorizationManagerProperty(UserCoreConstants.RealmConfig.PROPERTY_ADMINROLE_AUTHORIZATION);
        if (mgtPermissions != null) {
            String[] resourceIds = mgtPermissions.split(",");
            String adminRole = realmConfig.getAdminRoleName();
            for (String resourceId : resourceIds) {
                if (!this.isRoleAuthorized(adminRole, resourceId,
                        CarbonConstants.UI_PERMISSION_ACTION)) {
                    /* check whether admin role created in primary user store or as a hybrid role.
                     * if primary user store, & if not read only &/or if read ldap groups false,
                     * it is a hybrid role.
                     */
                    // as internal roles are created, role name must be appended with internal domain name
                    if (userRealm.getUserStoreManager().isReadOnly()) {
                        String readLDAPGroups = realmConfig.getUserStoreProperties().get(
                                LDAPConstants.READ_LDAP_GROUPS);
                        if (readLDAPGroups != null) {
                            if (!(Boolean.parseBoolean(readLDAPGroups))) {
                                this.authorizeRole(UserCoreConstants.INTERNAL_DOMAIN +
                                                CarbonConstants.DOMAIN_SEPARATOR +
                                                UserCoreUtil.removeDomainFromName(adminRole),
                                        resourceId, CarbonConstants.UI_PERMISSION_ACTION);
                                return;
                            }
                        } else {
                            this.authorizeRole(UserCoreConstants.INTERNAL_DOMAIN +
                                            CarbonConstants.DOMAIN_SEPARATOR +
                                            UserCoreUtil.removeDomainFromName(adminRole),
                                    resourceId, CarbonConstants.UI_PERMISSION_ACTION);
                            return;
                        }
                    }
                    //if role is in external primary user store, prefix admin role with domain name
                    adminRole = UserCoreUtil.addDomainToName(adminRole, realmConfig.getUserStoreProperty(
                            UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME));
                    this.authorizeRole(adminRole, resourceId, CarbonConstants.UI_PERMISSION_ACTION);
                }
            }
        }
    }

    @Override
    public String[] normalizeRoles(String[] roles) {
        if (roles != null && roles.length > 0) {
            int index = 0;
            List<String> normalizedRoles = new ArrayList<String>();
            for (String role : roles) {
                if ((index = role.indexOf(UserCoreConstants.TENANT_DOMAIN_COMBINER.toLowerCase())) >= 0) {
                    normalizedRoles.add(role.substring(0, index));
                } else {
                    normalizedRoles.add(role);
                }
            }
            return normalizedRoles.toArray(new String[normalizedRoles.size()]);
        }
        return roles;
    }

    private Object callSecure(final String methodName, final Object[] objects, final Class[] argTypes)
            throws UserStoreException {

        final JDBCAuthorizationManager instance = this;

        isSecureCall.set(Boolean.TRUE);
        final Method method;
        try {
            Class clazz = Class.forName("org.wso2.carbon.user.core.authorization.JDBCAuthorizationManager");
            method = clazz.getDeclaredMethod(methodName, argTypes);

        } catch (NoSuchMethodException e) {
            log.error("Error occurred when calling method " + methodName, e);
            throw new UserStoreException(e);
        } catch (ClassNotFoundException e) {
            log.error("Error occurred when calling class " + methodName, e);
            throw new UserStoreException(e);
        }

        try {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                @Override
                public Object run() throws Exception {
                    return method.invoke(instance, objects);
                }
            });
        } catch (PrivilegedActionException e) {
            if (e.getCause() != null && e.getCause().getCause() != null && e.getCause().getCause() instanceof
                    UserStoreException) {
                // Actual UserStoreException get wrapped with two exceptions
                throw new UserStoreException(e.getCause().getCause().getMessage(), e);

            } else {
                String msg = "Error occurred while accessing Java Security Manager Privilege Block";
                log.error(msg);
                throw new UserStoreException(msg, e);
            }
        } finally {
            isSecureCall.set(Boolean.FALSE);
        }
    }

}

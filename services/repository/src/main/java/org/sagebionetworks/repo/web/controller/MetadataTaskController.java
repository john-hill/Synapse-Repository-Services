package org.sagebionetworks.repo.web.controller;

import static org.sagebionetworks.repo.model.oauth.OAuthScope.modify;
import static org.sagebionetworks.repo.model.oauth.OAuthScope.view;

import java.io.IOException;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.metadata.ListMetadataTaskRequest;
import org.sagebionetworks.repo.model.metadata.ListMetadataTaskResponse;
import org.sagebionetworks.repo.model.metadata.MetadataTask;
import org.sagebionetworks.repo.service.ServiceProvider;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The Metadata Task services are used to manage <a href="${org.sagebionetworks.repo.model.metadata.MetadataTask}>Metadata Tasks</a>.
 * Metadata tasks are used to guide data contributors through the process of providing metadata in Synapse.
 */
@ControllerInfo(displayName = "Metadata Task Services", path = "repo/v1")
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class MetadataTaskController {

    @Autowired
    ServiceProvider serviceProvider;

    /**
     * Create a MetadataTask associated with a project.
     *
     * @param userId
     * @param metadataTask the MetadataTask to create
     * @return
     * @throws DatastoreException
     * @throws UnauthorizedException
     * @throws NotFoundException
     * @throws InvalidModelException
     * @throws IOException
     */
    @RequiredScope({view, modify})
    @ResponseStatus(HttpStatus.CREATED)
    @RequestMapping(value = UrlHelpers.METADATA_TASK, method = RequestMethod.POST)
    public @ResponseBody
    MetadataTask createMetadataTask(
            @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
            @RequestBody MetadataTask metadataTask) throws DatastoreException, UnauthorizedException, NotFoundException, InvalidModelException, IOException {
        return serviceProvider.getMetadataTaskService().createMetadataTask(userId, metadataTask);
    }

    /**
     * Get a MetadataTask by its ID.
     *
     * @param userId
     * @param taskId the MetadataTask to retrieve
     * @return
     * @throws DatastoreException
     * @throws UnauthorizedException
     * @throws NotFoundException
     * @throws InvalidModelException
     * @throws IOException
     */
    @RequiredScope({view, modify})
    @ResponseStatus(HttpStatus.OK)
    @RequestMapping(value = UrlHelpers.METADATA_TASK_ID, method = RequestMethod.GET)
    public @ResponseBody
    MetadataTask getMetadataTask(
            @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
            @PathVariable String taskId) throws DatastoreException, UnauthorizedException, NotFoundException, InvalidModelException, IOException {
        return serviceProvider.getMetadataTaskService().getMetadataTask(userId, taskId);
    }

    /**
     * Update a MetadataTask.
     *
     * @param userId
     * @param metadataTask the MetadataTask to update
     * @return
     * @throws DatastoreException
     * @throws UnauthorizedException
     * @throws NotFoundException
     * @throws InvalidModelException
     * @throws IOException
     */
    @RequiredScope({view, modify})
    @ResponseStatus(HttpStatus.OK)
    @RequestMapping(value = UrlHelpers.METADATA_TASK_ID, method = RequestMethod.PUT)
    public @ResponseBody
    MetadataTask updateMetadataTask(
            @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
            @RequestBody MetadataTask metadataTask) throws DatastoreException, UnauthorizedException, NotFoundException, InvalidModelException, IOException {
        return serviceProvider.getMetadataTaskService().updateMetadataTask(userId, metadataTask);
    }

    /**
     * Delete a MetadataTask.
     *
     * @param userId
     * @param taskId the MetadataTask to delete
     * @return
     * @throws DatastoreException
     * @throws UnauthorizedException
     * @throws NotFoundException
     * @throws InvalidModelException
     * @throws IOException
     */
    @RequiredScope({view, modify})
    @ResponseStatus(HttpStatus.OK)
    @RequestMapping(value = UrlHelpers.METADATA_TASK_ID, method = RequestMethod.DELETE)
    public @ResponseBody
    void deleteMetadataTask(
            @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,

            @PathVariable String taskId) throws DatastoreException, UnauthorizedException, NotFoundException, InvalidModelException, IOException {
        serviceProvider.getMetadataTaskService().deleteMetadataTask(userId, taskId);
    }



    /**
     * Get a list of MetadataTasks.
     *
     * @param userId
     * @param request the request to specify which MetadataTasks to retrieve
     * @return
     * @throws DatastoreException
     * @throws UnauthorizedException
     * @throws NotFoundException
     * @throws InvalidModelException
     * @throws IOException
     */
    @RequiredScope({view, modify})
    @ResponseStatus(HttpStatus.OK)
    @RequestMapping(value = UrlHelpers.METADATA_TASK_LIST, method = RequestMethod.POST)
    public @ResponseBody
    ListMetadataTaskResponse getListOfMetadataTasks(
            @RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
            @RequestBody ListMetadataTaskRequest request) throws DatastoreException, UnauthorizedException, NotFoundException, InvalidModelException, IOException {
        return serviceProvider.getMetadataTaskService().getMetadataTasks(userId, request);
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "configuration.h"
#include "worker-launcher.h"
#include "oci/oci.h"
#include "oci/oci_reap.h"

#include <errno.h>
#include <grp.h>
#include <limits.h>
#include <unistd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

#define _STRINGIFY(X) #X
#define STRINGIFY(X) _STRINGIFY(X)
#define CONF_FILENAME "worker-launcher.cfg"

#ifndef EXEC_CONF_DIR
  #error EXEC_CONF_DIR must be defined
#endif

void display_usage(FILE *stream) {
  fprintf(stream, "Usage: worker-launcher --checksetup\n");
  fprintf(stream, "Usage: worker-launcher user command command-args\n");
  fprintf(stream, "Commands:\n");
  fprintf(stream, "   initialize stormdist dir: code-dir <code-directory>\n");
  fprintf(stream, "   initialize artifacts dir: artifacts-dir <directory>\n");
  fprintf(stream, "   remove a file/directory: rmr <directory>\n");
  fprintf(stream, "   launch a worker: worker <working-directory> <script-to-run>\n");
  fprintf(stream, "   launch a profiler: profiler <working-directory> <script-to-run>\n");
  fprintf(stream, "   signal a worker: signal <pid> <signal>\n");
  fprintf(stream, "   launch a docker container: launch-docker-container <working-directory> <script-to-run>\n");
  fprintf(stream, "   run a docker command: run-docker-cmd <working-directory> <script-to-run>\n");
  fprintf(stream, "   profile a docker container: profile-docker-container <worker-id> <script-to-run>\n");
  fprintf(stream, "   launch an oci container:  run-oci-container <working-directory> <command-file> <worker-artifacts-dir>\n");
  fprintf(stream, "   reap an oci container: reap-oci-container <container-id> <num-reap-layers-keep>\n");
  fprintf(stream, "   profile a oci container: profile-oci-container <container-pid> <script-to-run>\n");
}

int main(int argc, char **argv) {
  int invalid_args = 0; 
  int do_check_setup = 0;
  
  LOGFILE = stdout;
  ERRORFILE = stderr;

  // Minimum number of arguments required to run 
  // the std. worker-launcher commands is 3
  // 3 args not needed for checksetup option
  if (argc < 3) {
    invalid_args = 1;
    if (argc == 2) {
      const char *arg1 = argv[1];
      if (strcmp("--checksetup", arg1) == 0) {
        invalid_args = 0;
        do_check_setup = 1;        
      }
    }
  }
  
  if (invalid_args != 0) {
    display_usage(stdout);
    return INVALID_ARGUMENT_NUMBER;
  }

  const char * command = NULL;
  const char * working_dir = NULL;

  int exit_code = 0;

  char *executable_file = get_executable();

  char *orig_conf_file = STRINGIFY(EXEC_CONF_DIR) "/" CONF_FILENAME;
  char *conf_file = realpath(orig_conf_file, NULL);

  if (conf_file == NULL) {
    fprintf(ERRORFILE, "Configuration file %s not found.\n", orig_conf_file);
    exit(INVALID_CONFIG_FILE);
  }
  if (do_check_setup) {
    fprintf(LOGFILE, "Using configuration file %s \n", conf_file);
  }
  if (check_configuration_permissions(conf_file) != 0) {
    exit(INVALID_CONFIG_FILE);
  }
  read_config(conf_file);

  // look up the worker launcher group in the config file
  char *wl_group = get_value(LAUNCHER_GROUP_KEY);
  if (wl_group == NULL) {
    fprintf(ERRORFILE, "Can't get configured value for %s.\n", LAUNCHER_GROUP_KEY);
    exit(INVALID_CONFIG_FILE);
  }
  struct group *group_info = getgrnam(wl_group);
  if (group_info == NULL) {
    fprintf(ERRORFILE, "Can't get group information for %s - %s.\n", wl_group,
            strerror(errno));
    fflush(LOGFILE);
    exit(INVALID_CONFIG_FILE);
  }

  set_launcher_uid(getuid(), group_info->gr_gid);
  // if we are running from a setuid executable, make the real uid root
  setuid(0);
  // set the real and effective group id to the node manager group
  setgid(group_info->gr_gid);

  if (check_executor_permissions(executable_file) != 0) {
    fprintf(LOGFILE, "worker-launcher config file: %s \n", conf_file);
    free(conf_file);
    conf_file = NULL;
    fprintf(ERRORFILE, "ERROR: Invalid permissions on worker-launcher binary.\n");
    return INVALID_CONTAINER_EXEC_PERMISSIONS;
  }

  free(executable_file);
  free(conf_file);
  conf_file = NULL;

  if (do_check_setup != 0) {
    // basic setup checks done
    // verified configs available and valid
    // verified executor permissions
    return 0;
  }

  //checks done for user name
  const char *user_name = argv[optind];
  if (user_name == NULL) {
    fprintf(ERRORFILE, "Invalid user name.\n");
    return INVALID_USER_NAME;
  }

  int ret = set_user(user_name);
  if (ret != 0) {
    return ret;
  }
 
  optind = optind + 1;
  command = argv[optind++];

  fprintf(LOGFILE, "main : command provided %s\n",command);
  fprintf(LOGFILE, "main : user is %s\n", user_detail->pw_name);
  fflush(LOGFILE);

  if (strcasecmp("code-dir", command) == 0) {
    if (argc != 4) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 4) for code-dir\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      exit_code = setup_dir_permissions(argv[optind], 0, TRUE);
    }
  } else if (strcasecmp("artifacts-dir", command) == 0) {
    if (argc != 4) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 4) for artifacts-dir\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      exit_code = setup_dir_permissions(argv[optind], 1, TRUE);
    }
  } else if (strcasecmp("blob", command) == 0) {
      if (argc != 4) {
        fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 4) for blob\n", argc);
        fflush(ERRORFILE);
        exit_code = INVALID_ARGUMENT_NUMBER;
      } else {
        exit_code = setup_dir_permissions(argv[optind], 0, TRUE);
      }
  } else if (strcasecmp("rmr", command) == 0) {
    if (argc != 4) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 4) for rmr\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      exit_code = recursive_delete(argv[optind], 1);
    }
  } else if (strcasecmp("worker", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for worker\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      working_dir = argv[optind++];
      exit_code = setup_dir_permissions(working_dir, 1, TRUE);
      if (exit_code == 0) {
        exit_code = setup_worker_tmp_permissions(working_dir);
        if (exit_code == 0) {
          exit_code = exec_as_user(working_dir, argv[optind]);
        }
      }
    }
  } else if (strcasecmp("launch-docker-container", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for launch-docker-container\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      working_dir = argv[optind++];
      exit_code = setup_dir_permissions(working_dir, 1, TRUE);
      if (exit_code == 0) {
        exit_code = setup_worker_tmp_permissions(working_dir);
        if (exit_code == 0) {
          exit_code = run_docker_cmd(working_dir, argv[optind]);
        }
      }
    }
  } else if (strcasecmp("run-docker-cmd", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for run-docker-cmd\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      working_dir = argv[optind++];
      exit_code = run_docker_cmd(working_dir, argv[optind]);
    }
  } else if (strcasecmp("profile-docker-container", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for profile-docker-container\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      const char * worker_id = argv[optind++];
      int pid = get_docker_container_pid(worker_id);
      exit_code = profile_oci_container(pid, argv[optind]);
    }
  } else if (strcasecmp("profiler", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for profiler\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      working_dir = argv[optind++];
      exit_code = exec_as_user(working_dir, argv[optind]);
    }
  } else if (strcasecmp("signal", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for signal\n",
	      argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      char* end_ptr = NULL;
      char* option = argv[optind++];
      int container_pid = strtol(option, &end_ptr, 10);
      if (option == end_ptr || *end_ptr != '\0') {
        fprintf(ERRORFILE, "Illegal argument for container pid %s\n", option);
        fflush(ERRORFILE);
        exit_code = INVALID_ARGUMENT_NUMBER;
      } else {
        option = argv[optind++];
        int signal = strtol(option, &end_ptr, 10);
        if (option == end_ptr || *end_ptr != '\0') {
          fprintf(ERRORFILE, "Illegal argument for signal %s\n", option);
          fflush(ERRORFILE);
          exit_code = INVALID_ARGUMENT_NUMBER;
        } else {
          exit_code = signal_container_as_user(user_detail->pw_name, container_pid, signal);
        }
      }
    }
  } else if (strcasecmp("run-oci-container", command) == 0) {
    if (argc != 6) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 6) for run-oci-container\n", argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      working_dir = argv[optind++];
      const char* command_file = argv[optind++];
      const char* worker_artifacts_dir = argv[optind];
      exit_code = setup_dir_permissions(working_dir, 1, TRUE);
      if (exit_code == 0) {
        exit_code = setup_worker_tmp_permissions(working_dir);
        if (exit_code == 0) {
          //becomes root.
          setuid(0);
          exit_code = run_oci_container(command_file, worker_artifacts_dir);
        }
      }
    }
  } else if (strcasecmp("reap-oci-container", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for reap-oci-container\n",
	      argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      char* container_id = argv[optind++];
      int num_reap_layers_keep = atoi(argv[optind]);
      //becomes root.
      setuid(0);
      exit_code = cleanup_oci_container_by_id(container_id, num_reap_layers_keep);
    }
  } else if (strcasecmp("profile-oci-container", command) == 0) {
    if (argc != 5) {
      fprintf(ERRORFILE, "Incorrect number of arguments (%d vs 5) for profile-oci-container\n",
	      argc);
      fflush(ERRORFILE);
      exit_code = INVALID_ARGUMENT_NUMBER;
    } else {
      char* container_pid = argv[optind++];
      exit_code = profile_oci_container(atoi(container_pid), argv[optind]);
    }
  } else {
    fprintf(ERRORFILE, "Invalid command %s not supported.",command);
    fflush(ERRORFILE);
    exit_code = INVALID_COMMAND_PROVIDED;
  }
  fclose(LOGFILE);
  fclose(ERRORFILE);
  return exit_code;
}

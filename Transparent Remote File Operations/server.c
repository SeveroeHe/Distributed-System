/* This is rpc server that set up for handling multiple user request concurrently
 * - for each connection from user, it will first receive integer byte of op code to
 *	 decide which rpc command it is calling.
 * - It will also fork each user request into a child process in order to handle 
 *	 concurrent client
 * - within each rpc function it will receive params and call related command function
 *	 then pack necessary return value, environment variable and other pointer values
 *	 send back to client.
 */
#define _GNU_SOURCE
#include <stdarg.h>
#include <stdio.h>
#include <dlfcn.h>
#include <dirent.h>
#include <stdlib.h>
#include <stdbool.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <fcntl.h>
#include <stdarg.h>
#include <errno.h>

#include "../include/dirtree.h"

//DEFINE MACROS
#define MAXMSGLEN 100
// #define fprintf(...) (void)0; 

void se_open(int sessfd, int rv);
void se_read(int sessfd, int rv);
void se_close(int sessfd, int rv);
void se_write(int sessfd, int rv);
void se_lseek(int sessfd, int rv);
void se_xstat(int sessfd, int rv);
void se_unlink(int sessfd, int rv);
void se_getdirentry(int sessfd, int rv);
void se_getdirtree(int sessfd, int rv);

int treesize(struct dirtreenode *node);
void srltree(void *buf, int *pos, struct dirtreenode *node);

int main(int argc, char**argv) {
	int op;

	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short)atoi(serverport);
	else port=22410;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);
	
	// start listening for connections
	rv = listen(sockfd, 6);	//start queue up to 5
	if (rv<0) err(1,0);

	// main server loop, handle clients one at a time, quit after 10 clients
	while(true) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		if (sessfd<0) err(1,0);
		//fork and run request in child process, handlng concurrent requests
		rv = fork();
		if(rv == 0) {
			close(sockfd);
			// get messages and send replies to this client, until it goes away
			while ( (rv=recv(sessfd, &op, sizeof(int), 0)) > 0) {
				fprintf(stderr, "server receive op %d\n", op);
				/*parse buffer first ele, decide operation, then call different function*/
				switch(op) {
					case 0: se_open(sessfd,rv);break;
					case 1: se_read(sessfd, rv);break;
					case 2: se_close(sessfd, rv);break;
					case 3: se_write(sessfd, rv);break;
					case 4: se_lseek(sessfd, rv);break;
					case 5: se_xstat(sessfd, rv);break;
					case 6: se_unlink(sessfd, rv);break;
					case 7: se_getdirentry(sessfd, rv);break;
					case 8: se_getdirtree(sessfd, rv);break;
				}
			}
			// either client closed connection, or error
			if (rv<0) err(1,0);
			close(sessfd);
			exit(0);
		}
		close(sessfd);
	}
	close(sockfd);
	return 0;
}

/* @params: buffer packing serialized data, free position on buffer, tree node
 * use DFS, recursively get name length, name, 
 * child number of a treenode, pack into buffer
 */
void srltree(void *buf, int *pos, struct dirtreenode *node) {
	int freepos = *pos,i = 0;
	int namelen = strlen(node->name)+1;	//also copy null terminate together

	memcpy(buf+freepos, &namelen, sizeof(int));
	freepos += sizeof(int);
	memcpy(buf+freepos, node->name, namelen);
	freepos += namelen;
	memcpy(buf+freepos, &(node->num_subdirs), sizeof(int));
	freepos += sizeof(int);

	*pos = freepos;
	fprintf(stderr, "	within loop freepos %d\n", *pos);
	for(i = 0; i < node->num_subdirs;i++) {
		srltree(buf, pos, (node->subdirs)[i]);
	}
}
/* @params: tree node
 * recursively get total size of tree,
 * in order to set up a valid buffer to store necessary data
 * the necessary size for each node on tree buffer is 
 *	- pathname(string), length of pathname(integer), child number(integer)
 */
int treesize(struct dirtreenode *node) {
	int nodesize = 0;
	int i = 0;
	nodesize += strlen(node->name)+1+sizeof(int)*2;	// include string,\0,its size, subdir numbers
	for(i = 0; i < node->num_subdirs;i++) {
		nodesize += treesize((node->subdirs)[i]);
	}
	return nodesize;
}
/* handle open request from client
 * receive flags(int), mode(mode_t), path length(int) and then dynamically set buffer to receive pathname
 * 	-if fail, return -1, set errno
 *  -if success, return valid file descriptor
 * pack variables and then send back to client
 */ 
void se_open(int sessfd, int rv) {
	int flags;
	int pathlen;
	int freepos = 0;
	mode_t m;
	//receive request
	char *ret_msg = malloc(MAXMSGLEN);
	rv = recv(sessfd, &flags, sizeof(int),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, &m, sizeof(mode_t),0);
	if (rv<0) err(1,0);		
	rv = recv(sessfd, &pathlen, sizeof(int), 0);
	if (rv<0) err(1,0);
	char *filepath = malloc(pathlen+1);
	rv = recv(sessfd, filepath ,pathlen,0);
	if (rv<0) err(1,0);		
	filepath[pathlen] = 0;
	fprintf(stderr, "server open flags %d path |%s| mode %d\n", flags,filepath, m);
	//execute origin function
	int ret = open(filepath, flags, m);
	memcpy(ret_msg, &ret, sizeof(int));
	freepos += sizeof(int);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	fprintf(stderr, "	server open send back %d\n", ret);
	//send back data
	send(sessfd, ret_msg, freepos, 0);	
	free(filepath);
	free(ret_msg);
}
/*handle read request from user
 * it will receive file descriptor(int), nbyte(int) from the user
 *	- if read success, return length of bytes read
 *  - if fail, return -1, set errno message
 * pack length being read(int), errno value(int);
 * if success also pack content length(int), content(string) into buffer
 * and then send back to client
 */
void se_read(int sessfd, int rv) {
	int fd;
	int freepos = 0;
	size_t nbyte;
	//receive serialized data from client
	rv = recv(sessfd, &fd, sizeof(int),0);
	rv = recv(sessfd, &nbyte, sizeof(nbyte),0);
	char *content = malloc(nbyte+MAXMSGLEN);
	char *ret_msg = malloc(nbyte+MAXMSGLEN);
	fprintf(stderr, "server read has param: |%d| |%ld|\n", fd, nbyte);
	//execute origin function
	ssize_t length = read(fd, content, nbyte);
	//serialize response and send back to client
	memcpy(ret_msg, &length, sizeof(ssize_t));
	freepos += sizeof(ssize_t);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	if(length > 0) {
		content[length] = '\0';
		memcpy(ret_msg+freepos, content, length);
		freepos += length;
		fprintf(stderr, "	server: read success");
	}
	//recursively send data to make sure total bytes has been sent
	size_t sd_cnt = 0;
	while(sd_cnt != freepos) {
		rv = send(sessfd, ret_msg+sd_cnt, freepos-sd_cnt, 0);
		sd_cnt += rv;
	}
	free(content);
	free(ret_msg);
}
/* handle close request of user
 * if will receive file descriptor(int) from user
 * pack return value and environment variable into buffer
 * send pack to user
 */
void se_close(int sessfd, int rv) {
	int fd;
	int freepos = 0;
	char *ret_msg = malloc(MAXMSGLEN);
	// receive data from client
	rv = recv(sessfd, &fd, sizeof(int),0);
	fprintf(stderr, "server: close has param: |%d|\n",fd);
	//execute origin function
	int response = close(fd);
	memcpy(ret_msg, &response, sizeof(int));
	freepos += sizeof(int);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	//send back serailized data
	send(sessfd, ret_msg, freepos, 0);
	free(ret_msg);
}
/* handle write request from client
 * it will revceive byte number(size_t), file descriptor(int) and content from user
 * pack return value from write, and errno value back to client.
 */
void se_write(int sessfd, int rv) {
	int fd;
	int freepos = 0;
	size_t nbyte;
	recv(sessfd, &nbyte, sizeof(size_t),0);
	recv(sessfd, &fd, sizeof(int),0);
	char *content = malloc(nbyte+1);
	size_t rcv_count = 0;
	// recursively receive data to make sure total content has been received
	while(rcv_count != nbyte) {
		rv = recv(sessfd, content+rcv_count, nbyte - rcv_count,0);
		rcv_count += rv;
	}
	content[nbyte] = 0;
	// form return response
	char *ret_msg = malloc(MAXMSGLEN+1);
	int ret = write(fd, content, nbyte);
	memcpy(ret_msg, &ret, sizeof(int));
	freepos += sizeof(int);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	//send back response
	send(sessfd, ret_msg, freepos, 0);	
	free(content);
	free(ret_msg);
}
/* handle lseek request from user
 * receive file descriptor(int), offset(off_t), whence(int) from users
 * return updated offset and errno value to client
 */
void se_lseek(int sessfd, int rv) {
	int fd;
	int freepos = 0;
	char *ret_msg = malloc(MAXMSGLEN);
	off_t offset;
	int whence;
	// receive data from user
	rv = recv(sessfd, &fd, sizeof(int),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, &offset, sizeof(off_t),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, &whence, sizeof(int), 0);
	if (rv<0) err(1,0);
	fprintf(stderr, "server lseek: fd %d offset %ld whence%d\n", fd, offset, whence);
	//execute origin function
	off_t ret = lseek(fd, offset, whence);
	//form response
	memcpy(ret_msg, &ret, sizeof(off_t));
	freepos += sizeof(off_t);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	fprintf(stderr, "	server lseek: send back offset%ld\n", ret);
	//send response to client
	send(sessfd, ret_msg, freepos, 0);
	free(ret_msg);
}
/* handle stat request from user
 * receive ver(int), path length(int), path(string) from user
 * it will receive a stat structure if function return success
 * pack ret value, errno value, stat structure(if success) and
 * send to users
 */
void se_xstat(int sessfd, int rv) {
	int ver;
	int pathlen;
	int freepos = 0;
	struct stat *buf = malloc(sizeof(struct stat));
	// receive data from user, deserialize data
	rv = recv(sessfd, &ver, sizeof(int),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, &pathlen, sizeof(int), 0);
	if(rv<0) err(1,0);
	fprintf(stderr, "server stat ver%d, strlen%d\n", ver,pathlen);
	char* path = malloc(pathlen + 1);
	rv = recv(sessfd, path, pathlen,0);
	if (rv<0) err(1,0);
	path[pathlen] = 0;
	fprintf(stderr, "	server stat: |%d| |%s|\n", ver, path);
	//execute origin function
	int ret = __xstat(ver, path, buf);
	// set up buffer, form response
	char *ret_msg = malloc(sizeof(int)*2+sizeof(struct stat));
	memcpy(ret_msg, &ret, sizeof(int));
	freepos += sizeof(int);
	memcpy(ret_msg+freepos, &errno,sizeof(int));
	freepos += sizeof(int);
	if(ret >= 0) {
		memcpy(ret_msg+freepos, buf, sizeof(struct stat));
		freepos += sizeof(struct stat);
	}
	fprintf(stderr, "server sendback %d |%s|\n", ret, (char *) buf);
	// send back response
	send(sessfd, ret_msg, freepos,0);
	free(path);
	free(buf);
	free(ret_msg);
}
/* handle unlink request from user
 * it will receive path length(int), path name(string) from user
 * pack the return value of unlink function and errno value, send back to client
 */
void se_unlink(int sessfd, int rv) {
	int freepos = 0;
	int pathlen;
	char *ret_msg = malloc(MAXMSGLEN);
	//receive serailized data from user
	rv = recv(sessfd, &pathlen, sizeof(int),0);
	if (rv<0) err(1,0);
	char *path = malloc(pathlen+1);
	rv = recv(sessfd, path, pathlen,0);
	if (rv<0) err(1,0);
	path[pathlen] = 0;
	//execute origin function
	int ret = unlink(path);
	//form response
	memcpy(ret_msg, &ret, sizeof(int));
	freepos += sizeof(int);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	fprintf(stderr, "server unlink ret value %d\n", ret);
	//send response back to client
	send(sessfd, ret_msg, freepos, 0);
	free(ret_msg);
	free(path);
}
/* handle getdirentry request from user
 * receive file descriptor(int), byte(size_t), basep pointer value(off_t)
 * get return value, directory content and updated basep pointer from origin function
 * pack return value, errno value, content and basep and send back to client
 */
void se_getdirentry(int sessfd, int rv) {
	int fd;
	int freepos = 0;
	size_t nbyte;
	off_t *basep = malloc(sizeof(off_t));
	//receive serialized data from user
	rv = recv(sessfd, &fd, sizeof(int),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, &nbyte, sizeof(size_t),0);
	if (rv<0) err(1,0);
	rv = recv(sessfd, basep, sizeof(off_t),0);
	if (rv<0) err(1,0);
	fprintf(stderr, "server getdit received |%d %ld %ld|\n", fd, nbyte, *basep);

	char *content = malloc(nbyte);
	char *ret_msg = malloc(nbyte+MAXMSGLEN);
	// execute origin function
	ssize_t ret = getdirentries(fd, content, nbyte, basep);
	//form response
	memcpy(ret_msg, &ret, sizeof(ssize_t));
	freepos += sizeof(ssize_t);
	memcpy(ret_msg+freepos, &errno, sizeof(int));
	freepos += sizeof(int);
	if( ret > 0) {
		//if success, pack dir content, updated basep pointer to buffer
		content[ret] = '\0';
		memcpy(ret_msg+freepos, content, ret);
		freepos += ret;
		memcpy(ret_msg+freepos, basep, sizeof(off_t));
		freepos += sizeof(off_t);
		fprintf(stderr, "server getdirsendsend |%ld %s %ld|\n", ret, content, *basep);
	}
	//send back response
	send(sessfd, ret_msg, freepos, 0);
	free(content);
	free(ret_msg);
	free(basep);
}
/* handle getdirtree request from user
 * receive path length(int), path(string)from user
 * the origin function return a directory tree structure
 * it will tranverse through tree to get tree size by calling "treesize" 
 * and set valid buffer, loop thr tree again by DFS by calling "srltree"
 * and fill in valid data (e.g. child number, name, name length) 
 * into buffer, send back to user
 */
void se_getdirtree(int sessfd, int rv) {
	int freepos = 0;
	int pathlen;
	//receive serialized data from user
	rv = recv(sessfd, &pathlen, sizeof(int), 0);
	if (rv<0) err(1,0);				
	char *path = malloc(pathlen+1);
	rv = recv(sessfd, path, pathlen, 0);
	if (rv<0) err(1,0);
	path[pathlen] = 0;
	fprintf(stderr, "server dirtree receive |%s|\n", path);

	struct dirtreenode* ret;
	//execute origin function, get tree structure
	ret = getdirtree(path);
	int totalsize = 0;
	void *ret_msg;
	if(ret == NULL) {	//indicating not a valid tree or empty tree
		ret_msg = malloc(sizeof(int)*2);
		memcpy(ret_msg, &totalsize, sizeof(int));	//indicate 0 length node
		freepos += sizeof(int);
		memcpy(ret_msg +freepos, &errno, sizeof(int));
		freepos += sizeof(int);
		fprintf(stderr, "server getdirtree empty tree\n");
		//send back response
		send(sessfd, ret_msg, freepos, 0);
	} else {
		void *treebuf;
		totalsize = treesize(ret);	//get total tree size
		treebuf = malloc(totalsize); // set up buffer 
		int position = 0;
		srltree(treebuf, &position, ret); // store serialized data
		fprintf(stderr, "	check length tt %d, pos %d\n", totalsize, position);
		//form response
		ret_msg = malloc(totalsize+sizeof(int));
		memcpy(ret_msg, &position, sizeof(int));
		freepos += sizeof(int);
		memcpy(ret_msg+freepos, treebuf, totalsize);
		freepos += totalsize;
		// send back request
		send(sessfd, ret_msg, freepos, 0);
		free(treebuf);
	}
	freedirtree(ret);
	free(ret_msg);
	free(path);
}

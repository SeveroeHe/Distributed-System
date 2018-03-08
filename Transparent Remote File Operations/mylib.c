/* This interpose library is used to make rpc calls to a remote rpc server,
 * 	- functions include open, read, write, close, lseek, stat, getdirentries,unlink
 * 	  as well as another self-build function getdirentries and freedirentries
 * 	- Generally these functions pack params and send to server. Some of the functions 
 * 	  have to check whether the input function is a valid rpc call.
 * 	- If it is an RPC call, function will receive related results from server
 *	  and unpack params, set related envirnment variable then send to user locally.
 */
#define _GNU_SOURCE

#include <dlfcn.h>
#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <err.h>
#include <errno.h>
#include <dirent.h>

#include "../include/dirtree.h"
/* define macro, the fd_offset is the one used for
 * distinguishing local & server-delivered file descriptors
 */
#define MAXMSGLEN 100
#define fd_offset 20000	
// #define fprintf(...) (void)0; 

/*global variable */
int sockfd;
int rv;
struct sockaddr_in srv;


void buildtree(struct dirtreenode *node, int *leng, int sockfd, int rv);

ssize_t (*orig_read)(int fildes, void *buf, size_t nbyte);
int (*orig_close)(int fd);
ssize_t (*orig_write)(int fd, const void *buf, size_t count);
off_t (*orig_lseek)(int fd, off_t offset, int whence);
int (*orig_getdirentries)(int fd, char *buf, int nbytes, long *basep);
void (*orig_freedirtree)( struct dirtreenode* dt );

/* wrapper function for open function
 * pass params to server, get file descriptors from server
 * 	- It will set up a buffer with size that could contain
 *  - opcode(Integer), flags(integer), mode(mode_t), pathname length(integer) and pathname(string)
 * return: a file descriptor add on offset, in order to distinguish
 * with local file descriptors
 */
int open(const char *pathname, int flags, ...) {
	int pathlen = strlen(pathname);
	char *msg = malloc(sizeof(int)*3+sizeof(mode_t)+pathlen);
	int op = 0;
	int freepos = 0;
	mode_t m=0;
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}
	/*form a request by op + params, send to server*/
	memcpy(msg, &op,sizeof(int));
	freepos +=sizeof(int);
	memcpy(msg+freepos, &flags,sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &m, sizeof(mode_t));
	freepos += sizeof(mode_t);
	memcpy(msg+freepos, &pathlen, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, pathname, pathlen);
	freepos += pathlen;

	fprintf(stderr, "client open op %d flags %d path|%s|\n", op, flags, pathname);
	send(sockfd, msg, freepos, 0);	// send message; should check return value
	free(msg);
	// get message back
	int fd_ret;
	rv = recv(sockfd, &fd_ret, sizeof(int), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno variable
	if (rv<0) err(1,0);	
	fprintf(stderr, "	client open :errno%d\n", errno);
	if(fd_ret >= 0) {
		fd_ret += fd_offset;
	}
	fprintf(stderr, "	client open received %d\n", fd_ret);
	return fd_ret;
}
/* wrapper function for read
 * it will first judge whether it's a valid/rpc server fd
 * 	- if local, pass params with origin read
 *	- if rpc, pack params, send to server
 * return: length of content being read
 */
ssize_t read(int fildes, void *buf, size_t nbyte){		
	int op = 1;
	int freepos = 0;
	// judge whether it's a valid fd
	int fd = fildes - fd_offset;
	if(fd < 0) {
		return orig_read(fildes, buf, nbyte);
	}
	char *msg = malloc(sizeof(int)*2+sizeof(size_t));
	memcpy(msg, &op,sizeof(int));
	freepos +=sizeof(int);
	memcpy(msg+freepos, &fd, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &nbyte, sizeof(size_t));
	freepos += sizeof(size_t);
	// send message to server
	fprintf(stderr, "client read send %d, size%ld\n", fd, nbyte);
	send(sockfd, msg, freepos, 0);	// send message; should check return value
	free(msg);
	// get message back
	ssize_t read_len;
	rv = recv(sockfd, &read_len, sizeof(ssize_t), 0);	// get length of read
	if (rv<0) err(1,0);			// in case something went wrong
	rv = recv(sockfd, &errno, sizeof(int),0);
	if (rv<0) err(1,0);	
	if(read_len>0){
		/* make sure total bytes of content will be received*/
		ssize_t rcv_cnt = 0;
		while(rcv_cnt != read_len) {
			rv = recv(sockfd, buf+rcv_cnt, read_len - rcv_cnt,0);
			if (rv<0) err(1,0);	
			rcv_cnt += rv;
		}
		fprintf(stderr, "	client read recv %ld,rv %d \n", read_len, rv);
	}
	return read_len;
}
/* wrapper function for close
 * it will first judge if it's an RPC call
 *  -if local, pass params to origin close
 *  -if rpc, send to server
 */
int close(int fd){
	int op = 2;
	int freepos = 0;
	int fildes = fd - fd_offset;
	if(fildes < 0) {
		return orig_close(fd);
	}
	char *msg = malloc(sizeof(int)*2);
	memcpy(msg, &op,sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos,&fildes,sizeof(int));
	freepos += sizeof(int);
	// send message to servr
	fprintf(stderr, "client closed send : %d\n", fildes);
	send(sockfd, msg, freepos, 0);
	free(msg);
	// get message back
	int close_ret;
	rv = recv(sockfd, &close_ret, sizeof(int), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
	if (rv<0) err(1,0);	
	// }
	return close_ret;
}
/* wrapper function for write
 * - if local call, pass to origin write
 * - if rpc, pack params to server
 * return bytes being write
 */
ssize_t write(int fd, const void *buf, size_t count){
	int op = 3;
	int freepos = 0;
	int fildes = fd - fd_offset;
	if(fildes < 0) {
		return orig_write(fd, buf, count);
	}
	char *msg = malloc(sizeof(int)*2+sizeof(size_t)+count);
	fprintf(stderr, "clientwrite: fildes|%d|, count|%ld|\n", fildes, count);
	//form request: op|count|fd|buf
	memcpy(msg, &op,sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &count, sizeof(size_t));
	freepos += sizeof(size_t);
	memcpy(msg+freepos, &fildes, sizeof(int));
	freepos += sizeof(int);	
	memcpy(msg+freepos, buf, count);
	freepos += count;
	fprintf(stderr, "\n\n 	client: write send |%s|\n", msg+freepos-count);
	/*make sure total bytes has been sent*/
	size_t sd_cnt = 0;
	while(sd_cnt != freepos) {
		rv = send(sockfd, msg+sd_cnt, freepos-sd_cnt, 0);
		sd_cnt += rv;
	}	
	free(msg);
	// get message back
	int ret;
	rv = recv(sockfd, &ret, sizeof(int), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
	if (rv<0) err(1,0);
	return ret;
}

/* wrapper function for lseek
 * - if local call, pass params to origin lseek
 * - if rpc, pack params to server
 * return: new updated offset 
 */
off_t lseek(int fd, off_t offset, int whence){
	int op = 4;
	int freepos = 0;
	int fildes = fd - fd_offset;
	if(fildes < 0) {
		return orig_lseek(fd, offset, whence);
	}
	char *msg = malloc(sizeof(int)*3+sizeof(off_t));
	//form request
	memcpy(msg, &op, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &fildes, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &offset, sizeof(off_t));
	freepos += sizeof(off_t);
	memcpy(msg+freepos, &whence, sizeof(int));
	freepos += sizeof(int);
	fprintf(stderr, "client lseek fildes %d offset %ld whence %d\n", fildes, offset, whence);
	send(sockfd, msg,freepos, 0);	
	free(msg);
	// get message back
	off_t off_ret;
	rv = recv(sockfd, &off_ret, sizeof(off_t), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	fprintf(stderr, "	client lseek get offset back %ld, rv %d\n", off_ret, rv);
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
	return off_ret;
}
/* wrapper function for stat
 * pack params and send to server
 * if success, assign struct stat pointer and return
 */
int __xstat(int ver, const char *path, struct stat *buf){
	int op = 5;
	int freepos = 0;
	int path_len = strlen(path);
	char* msg = malloc(sizeof(int)*3+path_len);
	memcpy(msg, &op, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &ver, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &path_len, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, path, path_len);
	freepos += path_len;
	//send message to server
	fprintf(stderr, "client stat: |%d |%s|\n", ver, path);
	send(sockfd, msg, freepos, 0);	
	
	// get message back
	int ret;
	rv = recv(sockfd, &ret, sizeof(int), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	fprintf(stderr, "	client stat receive |%d|\n", ret);
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
	if (rv<0) err(1,0);	
	if(ret >=0) {
		/*if success, assign updated stat struct*/
		rv = recv(sockfd, (struct stat *)buf, sizeof(struct stat), 0);
		if (rv<0) err(1,0);	
	}
	free(msg);
	return ret;
}
/* wrapper function for unlink
 * it will pass params to server, unlink objects specified by pathname
 * return 0 if sucess
 */
int unlink(const char *pathname) {
	int op = 6;
	int freepos = 0;
	int pathlen = strlen(pathname);
	char *msg = malloc(sizeof(int)*2+pathlen);
	memcpy(msg, &op, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &pathlen, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, pathname, pathlen);
	freepos += pathlen;
	send(sockfd, msg, freepos, 0);	
	free(msg);
	// get message back
	int ret;
	rv = recv(sockfd, &ret, sizeof(int), 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	fprintf(stderr, "client unlink receive %d\n", ret);
	rv = recv(sockfd, &errno, sizeof(int), 0); //set errno 
	if (rv<0) err(1,0);			// in case something went wrong
	return ret;
}
/* wrapper function for getdirentries
 * - if it is a local call, pass params to origin function
 * - if RPC, pack params to server
 * return total size of transfered bytes
 * if success, update basep pointer, assign content to buffer
 */
ssize_t getdirentries(int fd, char *buf, size_t nbytes, off_t *basep) {
	int op = 7;
	int freepos = 0;
	//judge whether it is an rpc call
	int fildes = fd - fd_offset;
	if(fildes < 0) {
		return orig_getdirentries(fd, buf, nbytes, basep);
	}
	char *msg = malloc(sizeof(int)*2+sizeof(size_t)+sizeof(off_t));
	memcpy(msg, &op, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &fildes, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &nbytes, sizeof(size_t));
	freepos += sizeof(size_t);
	memcpy(msg+freepos, basep, sizeof(off_t));
	freepos += sizeof(off_t);
	fprintf(stderr, "client getentry: |fildes %d nbyte %lu base %ld|\n",fildes, nbytes, *basep );
	send(sockfd, msg, freepos, 0);	
	free(msg);
	// get message back
	ssize_t ret;
	rv = recv(sockfd, &ret, sizeof(ssize_t), 0);	// get message
	fprintf(stderr, "	client getentry revc return value|%ld|\n", ret);
	if (rv<0) err(1,0);			// in case something went wrong
	rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
	if (rv<0) err(1,0);			
	if (ret >0){
		rv = recv(sockfd, buf, ret, 0);	// update buffer
		if (rv<0) err(1,0);	
		rv = recv(sockfd, basep, sizeof(off_t), 0);	// update baseo pointer
		if (rv<0) err(1,0);	
		buf[ret] = 0;
		fprintf(stderr, "client getentry revc |%ld %s %ld|\n", ret, buf,*basep);
	}
	return ret;
}
/* wrapper function for dirtreenode
 * pack dir path to server
 * receive serialized tree from server
 * construct tree, return root node
 */
struct dirtreenode* getdirtree( const char *path ){
	int op = 8;
	int freepos = 0;
	int pathlen = strlen(path);
	char *msg = malloc(sizeof(int)*2+pathlen);
	memcpy(msg, &op, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, &pathlen, sizeof(int));
	freepos += sizeof(int);
	memcpy(msg+freepos, path, pathlen);
	freepos += pathlen;
	send(sockfd, msg, freepos, 0);	
	fprintf(stderr, "client send dirtreenode %d pathlen %d |%s|\n", msg[0], pathlen, path);
	free(msg);
	// get message back
	struct dirtreenode* temp = malloc(sizeof(struct dirtreenode));
	int ret, pos = 0;
	rv = recv(sockfd, &ret, sizeof(int), 0);	// get message
	fprintf(stderr, "client received\n");
	if (rv<0) err(1,0);			// in case something went wrong

	if(ret == 0) {	//indicating a null tree
		rv = recv(sockfd, &errno, sizeof(int), 0);	// set errno
		if (rv<0) err(1,0);	
		return NULL;		
	}
	buildtree(temp, &pos, sockfd, rv);
	fprintf(stderr, "client recieve tt_receive %d, tt_build %d\n", ret, pos);
	return temp;
}
/* wrapper function for freedirtree
 * actually it is not an rpc call
 * return origin function with params
 */
void freedirtree( struct dirtreenode* dt ){
	fprintf(stderr, "client freeing trees\n");
	//if passing small packages, there might be delay due to TCP mechanisms
	return orig_freedirtree(dt);
}

// This function is automatically called when program is started
void _init(void) {
	orig_read = dlsym(RTLD_NEXT, "read");
	orig_close = dlsym(RTLD_NEXT, "close");
	orig_write = dlsym(RTLD_NEXT, "write");
	orig_lseek = dlsym(RTLD_NEXT, "lseek");
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");
	orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");
	//establish socket
	char *serverip;
	char *serverport;
	unsigned short port;

	// serverip = "127.0.0.1";
	serverip = getenv("server15440");
	if (serverip) {} //printf("Got environment variable server15440: %s\n", serverip);
	else {
		serverip = "127.0.0.1";
	}
	// Get environment variable indicating the port of the server
	// serverport = "22415";
	serverport = getenv("serverport15440");
	if (serverport){}// fprintf(stderr, "Got environment variable serverport15440: %s\n", serverport);
	else {
		serverport = "22410";
	}
	port = (unsigned short)atoi(serverport);
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			// in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port
	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) err(1,0);

}
/* @params: treenode, bytes of data used, socket fd, rv
 * function for building a tree locally based on data transfered from server
 * dynamically allocate memory, assign value to each treenode
 */
void buildtree(struct dirtreenode *node, int *leng, int sockfd, int rv) {
	int namelen,i = 0;
	rv = recv(sockfd, &namelen, sizeof(int), 0);	// get name length
	if (rv<0) err(1,0);
	node->name = malloc(namelen);
	rv = recv(sockfd, node->name, namelen, 0);	// assign name string
	if (rv<0) err(1,0);
	rv = recv(sockfd, &(node->num_subdirs), sizeof(int), 0);	// assign subdir numbers
	if (rv<0) err(1,0);
	*leng += sizeof(int)*2+namelen;
	if(node->num_subdirs != 0) {	//assign memory for childnode array
		node->subdirs = malloc(sizeof(node)*(node->num_subdirs));
	}
	fprintf(stderr, "	client within build, bd_count %d\n", *leng);
	// recursively build tree
	for(i = 0;i < node->num_subdirs;i++) {
		(node->subdirs)[i] = malloc(sizeof(struct dirtreenode));
		buildtree((node->subdirs)[i], leng, sockfd, rv);
	}
}